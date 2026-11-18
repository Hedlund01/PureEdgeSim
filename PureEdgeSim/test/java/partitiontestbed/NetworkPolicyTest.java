package partitiontestbed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.network.InfrastructureGraph;
import com.mechalikh.pureedgesim.network.NetworkLink;

import partitiontestbed.NetworkPolicy.Layer;
import partitiontestbed.baselines.AccessServerStrategy;

/**
 * Meshing within a layer, between layers, or not at all, as the strategy's
 * {@link NetworkPolicy} says, on the same links.
 */
class NetworkPolicyTest extends TestbedTestSupport {

	/**
	 * Two devices in radio range of each other, one of them in range of access
	 * server {@code a}; {@code a} and {@code b} are joined by the mesh, each with an
	 * uplink to the cloud.
	 */
	private final ComputingNode near = node();
	private final ComputingNode far = node();
	private final ComputingNode a = node();
	private final ComputingNode b = node();
	private final ComputingNode cloud = node();
	private final Map<ComputingNode, Layer> layers = new HashMap<>(
			Map.of(near, Layer.DEVICE, far, Layer.DEVICE, a, Layer.EDGE, b, Layer.EDGE, cloud, Layer.CLOUD));
	private final InfrastructureGraph graph = new InfrastructureGraph();
	private final List<NetworkLink> d2d = new ArrayList<>();
	private final List<NetworkLink> mesh = new ArrayList<>();

	NetworkPolicyTest() {
		both(near, a, 0.002);
		d2d.addAll(both(near, far, 0.001));
		mesh.addAll(both(a, b, 0.002));
		both(a, cloud, 0.03);
		both(b, cloud, 0.03);
	}

	private LiveTopology topology(NetworkPolicy policy) {
		return new LiveTopology(graph, policy, layers::get);
	}

	@Test
	void theDefaultHierarchyLetsNoDeviceRelay() {
		LiveTopology topology = topology(NetworkPolicy.hierarchical());
		assertTrue(topology.reachable(near, b), "a device reaches the edge through its access server");
		assertFalse(topology.reachable(far, a), "a device out of range of every server is cut off");
		assertFalse(topology.reachable(near, far), "and the radio between devices is not used");
		topology.cut(mesh, 1);
		assertFalse(topology.reachable(a, b), "nor is the cloud a hop between two servers");
		assertTrue(topology.reachable(a, cloud));
	}

	@Test
	void aMeshOfDevicesCarriesTrafficThroughThem() {
		LiveTopology topology = topology(NetworkPolicy.deviceMesh());
		assertEquals(0.001, topology.latency(near, far), 1e-12, "devices talk to each other directly");
		assertEquals(0.003, topology.latency(far, a), 1e-12, "and one relays for the other to its server");
		assertTrue(topology.reachable(far, cloud), "all the way to the cloud");
		assertTrue(topology.reachable(cloud, far), "and back");

		topology.enable(d2d.get(0), false, 5);
		topology.enable(d2d.get(1), false, 5);
		assertFalse(topology.reachable(far, a), "once out of range, the device is cut off again");
	}

	@Test
	void devicesCanTalkToEachOtherWithoutRelayingForTheEdge() {
		LiveTopology topology = topology(NetworkPolicy.hierarchical().link(Layer.DEVICE, Layer.DEVICE));
		assertTrue(topology.reachable(near, far), "a link between two devices is a path of its own");
		assertFalse(topology.reachable(far, a), "but no device forwards what is not its own");
	}

	@Test
	void aStrategyChoosesWhichNodeRelays() {
		LiveTopology topology = topology(NetworkPolicy.hierarchical().link(Layer.DEVICE, Layer.DEVICE));
		topology.setRelay(near, true);
		assertTrue(topology.reachable(far, b), "the device elected relay forwards for its neighbour");
		topology.setRelay(near, false);
		assertFalse(topology.reachable(far, b), "and stops when it is no longer elected");

		topology = topology(NetworkPolicy.hierarchical());
		topology.setRelay(a, false);
		assertFalse(topology.reachable(near, b), "an edge server can be told not to relay either");
		assertTrue(topology.reachable(near, a), "while it still answers for itself");
	}

	@Test
	void aStrategyCanUseTheHierarchyWithoutTheEdgeMesh() {
		LiveTopology topology = topology(NetworkPolicy.hierarchical().link(Layer.EDGE, Layer.EDGE, false));
		assertFalse(topology.reachable(a, b), "servers do not talk to each other");
		assertTrue(topology.reachable(near, cloud), "a device reaches the cloud straight up the hierarchy");
		topology = topology(NetworkPolicy.hierarchical().link(Layer.EDGE, Layer.EDGE, false).relay(Layer.CLOUD));
		assertEquals(0.06, topology.latency(a, b), 1e-12, "or through the cloud, when the cloud relays");
	}

	@Test
	void aRadioContactBetweenTwoGroupsBridgesThemOnlyWhereItIsLetThrough() {
		// far is in the other group of a partition: the link between the two devices
		// is the only one between the groups.
		LiveTopology topology = topology(NetworkPolicy.deviceMesh());
		boolean[] partitioned = { true };
		topology.setBarrier(link -> partitioned[0] && d2d.contains(link));
		assertFalse(topology.reachable(far, a), "the barrier keeps the groups apart");
		assertTrue(topology.isUp(d2d.get(0)), "though the link itself is up");
		partitioned[0] = false;
		topology.groupsChanged();
		assertTrue(topology.reachable(far, a), "the groups are joined again once the barrier lifts");
	}

	@Test
	void theDefaultPolicyIsTheHierarchy() {
		assertEquals("links device-edge edge-edge edge-cloud, relays edge", NetworkPolicy.hierarchical().toString());
		assertEquals("links device-device device-edge edge-edge edge-cloud, relays device edge, multi-homing",
				NetworkPolicy.deviceMesh().multiHoming(true).toString());
	}

	// ----------------------------------------------------------- integration

	/** Records what the radio of each device hears, under one policy. */
	static final class RadioStrategy extends AccessServerStrategy {
		static final String MESH = "D2D_MESH";
		static final String HIERARCHY = "HIERARCHY";
		static final List<Integer> contacts = new ArrayList<>();
		static final List<Double> delivered = new ArrayList<>();
		private final NetworkPolicy policy;

		RadioStrategy(NetworkPolicy policy) {
			this.policy = policy;
		}

		@Override
		public NetworkPolicy network() {
			return policy;
		}

		@Override
		public void initialize() {
			contacts.clear();
			delivered.clear();
			ctx.schedule(10, () -> {
				List<ComputingNode> devices = new ArrayList<>();
				for (EdgeServer server : ctx.accessServers())
					for (ComputingNode contact : ctx.radioContacts(server.node))
						if (ctx.serverOf(contact) == null && !devices.contains(contact))
							devices.add(contact);
				int heard = 0;
				for (ComputingNode device : devices)
					for (ComputingNode contact : ctx.radioContacts(device))
						if (ctx.serverOf(contact) == null) {
							heard++;
							ctx.send(device, contact, 200, ctx.now());
						}
				contacts.add(heard);
			});
		}

		@Override
		public void onMessage(ComputingNode node, ComputingNode from, Object payload) {
			if (payload instanceof Double)
				delivered.add(ctx.now() - (Double) payload);
		}
	}

	@Test
	@Tag("integration")
	void devicesInRangeHearEachOtherOnlyWhenTheirStrategyUsesThatRadio() throws Exception {
		Testbed.register(RadioStrategy.MESH, () -> new RadioStrategy(NetworkPolicy.deviceMesh().multiHoming(true)));
		Testbed.register(RadioStrategy.HIERARCHY, () -> new RadioStrategy(NetworkPolicy.hierarchical()));
		testbed("d2d.range_m", "120");

		String console = run(RadioStrategy.MESH);
		assertFalse(console.contains("unexpected error"), console);
		assertTrue(console.contains("relays device edge, multi-homing"), "the run prints the policy");
		assertEquals(1, RadioStrategy.contacts.size());
		assertTrue(RadioStrategy.contacts.get(0) > 0, "devices within range of each other are linked");
		assertEquals(RadioStrategy.contacts.get(0), RadioStrategy.delivered.size(),
				"and every message to a device it hears arrives");
		for (double delay : RadioStrategy.delivered)
			assertTrue(delay > 0 && delay < 1, "straight over the radio between them: " + delay);

		run(RadioStrategy.HIERARCHY);
		assertEquals(List.of(0), RadioStrategy.contacts, "the same devices hear nobody when the radio is unused");
	}

	// ---------------------------------------------------------------- helpers

	private static ComputingNode node() {
		return mock(ComputingNode.class);
	}

	private List<NetworkLink> both(ComputingNode x, ComputingNode y, double latency) {
		return List.of(link(x, y, latency), link(y, x, latency));
	}

	private NetworkLink link(ComputingNode from, ComputingNode to, double latency) {
		NetworkLink link = mock(NetworkLink.class);
		when(link.getSrc()).thenReturn(from);
		when(link.getDst()).thenReturn(to);
		when(link.getLatency()).thenReturn(latency);
		graph.addLink(link);
		return link;
	}
}
