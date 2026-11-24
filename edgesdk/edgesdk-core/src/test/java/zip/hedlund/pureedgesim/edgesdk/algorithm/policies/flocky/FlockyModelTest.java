package zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import zip.hedlund.pureedgesim.edgesdk.algorithm.Cancellable;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Declaration;
import zip.hedlund.pureedgesim.edgesdk.algorithm.LocalView;
import zip.hedlund.pureedgesim.edgesdk.algorithm.NodeLocal;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Reply;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Response;
import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.metrics.MetricsCollector;
import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/** Matching, scoring and discovery logic, without a simulation. */
class FlockyModelTest {

	private static final NodeRef A = NodeRef.of("a");
	private static final NodeRef B = NodeRef.of("b");
	private static final NodeRef C = NodeRef.of("c");
	private static final NodeRef D = NodeRef.of("d");
	private static final NodeRef E = NodeRef.of("e");

	private static NodeSummary node(NodeRef n, Set<String> traits, double reservedCores, double load,
			Map<String, Integer> deployed) {
		return new NodeSummary(n, 0, true, 8, 8_000, reservedCores, 0, load, traits,
				Set.of(ComponentDefinition.CONTAINER), deployed, List.of());
	}

	// ---- NodeSummary --------------------------------------------------------

	@Test
	void matchingNeedsDeploymentServiceWorkloadTypeAndRequiredTraits() {
		Component secure = Component.of("web").requires("attestation");
		ComponentDefinition plain = ComponentDefinition.of("web-c", "web", 1, 100);
		ComponentDefinition unikernel = plain.withWorkloadType("unikernel").provides("attestation");

		assertFalse(node(A, Set.of(), 0, 0, Map.of()).matches(secure, plain));
		assertTrue(node(A, Set.of("attestation"), 0, 0, Map.of()).matches(secure, plain));
		// the definition provides the trait, but the node cannot run unikernels
		assertFalse(node(A, Set.of(), 0, 0, Map.of()).matches(secure, unikernel));
		NodeSummary noDeployer = new NodeSummary(A, 0, false, 8, 8_000, 0, 0, 0, Set.of("attestation"),
				Set.of(ComponentDefinition.CONTAINER), Map.of(), List.of());
		assertFalse(noDeployer.matches(secure, plain));
	}

	@Test
	void fitsInUnreservedResourcesOrWhenAlreadyDeployed() {
		ComponentDefinition big = ComponentDefinition.of("big", "web", 6, 100);
		assertTrue(node(A, Set.of(), 2, 0, Map.of()).fits(big));
		assertFalse(node(A, Set.of(), 3, 0, Map.of()).fits(big));
		assertTrue(node(A, Set.of(), 8, 0, Map.of("big", 1)).fits(big));
	}

	// ---- QoeEvaluator -------------------------------------------------------

	@Test
	void legacyRanksByLatencyOnly() {
		Component web = Component.of("web");
		ComponentDefinition d = ComponentDefinition.of("web-c", "web", 1, 100);
		var near = new QoeEvaluator.Option(web, d, node(A, Set.of(), 7, 1, Map.of()), 0.01, 0.1);
		var far = new QoeEvaluator.Option(web, d, node(B, Set.of(), 0, 0, Map.of()), 0.05, 0.1);
		assertTrue(QoeEvaluator.LEGACY.score(near) > QoeEvaluator.LEGACY.score(far));
	}

	@Test
	void scoredWeighsFreeResourcesLoadAndOptionalTraits() {
		Component web = Component.of("web").prefers("green");
		ComponentDefinition d = ComponentDefinition.of("web-c", "web", 1, 100);
		QoeEvaluator scored = QoeEvaluator.scored();
		var busy = new QoeEvaluator.Option(web, d, node(A, Set.of(), 6, 0.9, Map.of()), 0.02, 0.1);
		var idle = new QoeEvaluator.Option(web, d, node(B, Set.of(), 0, 0, Map.of()), 0.02, 0.1);
		var green = new QoeEvaluator.Option(web, d, node(C, Set.of("green"), 0, 0, Map.of()), 0.02, 0.1);
		assertTrue(scored.score(idle) > scored.score(busy));
		assertTrue(scored.score(green) > scored.score(idle));
		// only closeness counts when the other weights are zero
		QoeEvaluator latencyOnly = QoeEvaluator.scored(1, 0, 0, 0);
		var far = new QoeEvaluator.Option(web, d, node(D, Set.of(), 0, 0, Map.of()), 0.08, 0.1);
		assertEquals(0.8, latencyOnly.score(idle), 1e-9);
		assertEquals(0.2, latencyOnly.score(far), 1e-9);
	}

	// ---- FlockyDiscovery ----------------------------------------------------

	@Test
	void adjustedDistanceWidensWhileFewNodesAreKnown() {
		assertEquals(0.1 * Math.sqrt(5), FlockyDiscovery.adjustedDistance(0.1, 5, 0), 1e-12);
		assertEquals(0.1 * Math.sqrt(5), FlockyDiscovery.adjustedDistance(0.1, 5, 1), 1e-12);
		assertEquals(0.1 * Math.sqrt(5.0 / 4), FlockyDiscovery.adjustedDistance(0.1, 5, 4), 1e-12);
		assertEquals(0.1, FlockyDiscovery.adjustedDistance(0.1, 5, 5), 1e-12);
		assertEquals(0.1, FlockyDiscovery.adjustedDistance(0.1, 5, 50), 1e-12);
	}

	@Test
	void aFarNodeIsASteppingStoneToTheNeighbourhood() {
		// a only knows b, out of range; b knows c and d, which are within range of a
		FakeNetwork net = new FakeNetwork();
		net.rtt(A, B, 0.5).rtt(A, C, 0.05).rtt(A, D, 0.06).knows(B, C, D).knows(C, D, A).knows(D, C);
		FlockyDiscovery discovery = new FlockyDiscovery(net, 0.1, 5, 1, 5);
		discovery.start(A, List.of(B));
		discovery.round(A);
		assertEquals(Set.of(C, D), discovery.known(A));
		assertEquals(0.05, discovery.rtt(A, C), 1e-12);
	}

	@Test
	void aSilentNodeIsDroppedUnlessItIsTheLastOneKnown() {
		FakeNetwork net = new FakeNetwork();
		net.rtt(A, C, 0.05).knows(C);
		FlockyDiscovery discovery = new FlockyDiscovery(net, 0.1, 5, 1, 5);
		discovery.start(A, List.of(B));
		discovery.round(A);
		assertEquals(Set.of(B), discovery.known(A), "the only node known is kept, even silent");

		discovery.start(A, List.of(C));
		discovery.round(A);
		assertEquals(Set.of(C), discovery.known(A));
	}

	@Test
	void nodesThatContactANodeAreCheckedInItsNextRound() {
		FakeNetwork net = new FakeNetwork();
		net.rtt(A, B, 0.02).rtt(A, E, 0.03).knows(B).knows(E);
		FlockyDiscovery discovery = new FlockyDiscovery(net, 0.1, 5, 1, 5);
		discovery.start(A, List.of(B));
		discovery.handle(A, E, FlockyDiscovery.PING);
		discovery.round(A);
		assertEquals(Set.of(B, E), discovery.known(A));
	}

	/**
	 * Answers discovery requests at once, from fixed round-trip times and node
	 * lists; a node with no round-trip time never answers.
	 */
	private static final class FakeNetwork implements SdkContext {

		final Map<NodeRef, Map<NodeRef, Double>> rtt = new HashMap<>();
		final Map<NodeRef, List<NodeRef>> knows = new HashMap<>();

		FakeNetwork rtt(NodeRef from, NodeRef to, double seconds) {
			rtt.computeIfAbsent(from, k -> new HashMap<>()).put(to, seconds);
			return this;
		}

		FakeNetwork knows(NodeRef node, NodeRef... others) {
			knows.put(node, List.of(others));
			return this;
		}

		@Override
		public void request(NodeRef from, NodeRef to, long bytes, Object payload, double timeout,
				Consumer<Reply> onReply, Runnable onTimeout) {
			Double d = rtt.getOrDefault(from, Map.of()).get(to);
			if (d == null || d > timeout) {
				onTimeout.run();
				return;
			}
			Object reply = payload instanceof FlockyDiscovery.Ping ? Boolean.TRUE
					: Response.of(knows.getOrDefault(to, List.of()), 0).payload();
			onReply.accept(new Reply(to, reply, d));
		}

		@Override
		public <T> NodeLocal<T> nodeLocal(Function<NodeRef, T> init) {
			Map<NodeRef, T> values = new HashMap<>();
			return new NodeLocal<>() {
				@Override
				public T at(NodeRef node) {
					return values.computeIfAbsent(node, init);
				}

				@Override
				public void set(NodeRef node, T value) {
					values.put(node, value);
				}
			};
		}

		@Override
		public Cancellable schedule(NodeRef at, double delay, Runnable action) {
			return CANCELLED;
		}

		@Override
		public Cancellable every(NodeRef at, double period, Runnable action) {
			return CANCELLED;
		}

		@Override
		public MetricsCollector metrics() {
			return new MetricsCollector() {
				@Override
				public void add(String name, double delta) {
				}

				@Override
				public void record(String name, double value) {
				}
			};
		}

		@Override
		public Random rng() {
			return new Random(1);
		}

		@Override
		public double now() {
			return 0;
		}

		@Override
		public DesignTopology topology() {
			throw new UnsupportedOperationException();
		}

		@Override
		public LocalView local(NodeRef at) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void send(NodeRef from, NodeRef to, long bytes, Object payload) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void declare(Declaration declaration) {
		}

		private static final Cancellable CANCELLED = new Cancellable() {
			@Override
			public void cancel() {
			}

			@Override
			public boolean isCancelled() {
				return true;
			}
		};
	}
}
