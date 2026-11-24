package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky.Component;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky.ComponentDefinition;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky.Flocky;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.metrics.TaskOutcome;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

@Tag("integration")
class FlockyIT {

	private static final NodeRef PA0 = NodeRef.of("pa-0");
	private static final NodeRef EDGE_A = NodeRef.of("edge-a");
	private static final NodeRef EDGE_B = NodeRef.of("edge-b");

	private Flocky flocky;

	private RunResult run(Supplier<Flocky> policy, String faults, Path out) {
		var b = TestScenarios.twoRegions(3);
		if (faults != null)
			b.withFaults(FaultScript.parse(faults));
		return b.build().run(Candidate.of("flocky", () -> flocky = policy.get()), out);
	}

	@Test
	void discoversTheNeighbourhoodAndDeploysNearby(@TempDir Path out) {
		RunResult r = run(Flocky::new, null, out);
		assertTrue(r.value(Metrics.TASK_SUCCESS) > 95, "success " + r.value(Metrics.TASK_SUCCESS));
		var known = flocky.discovery().known(PA0);
		assertTrue(known.containsAll(Set.of(EDGE_A, EDGE_B, NodeRef.of("pa-1"))), known.toString());
		assertEquals(EDGE_A, flocky.deployments(PA0).get("telemetry").node());
		assertTrue(flocky.hosted(EDGE_A).get("telemetry/generic").contains(PA0));
	}

	@Test
	void requiredTraitsSteerTheDeployment(@TempDir Path out) {
		RunResult r = run(() -> new Flocky().component(Component.of("telemetry").requires("attestation"))
				.traits(Select.name("edge-b"), "attestation"), null, out);
		assertTrue(r.value(Metrics.TASK_SUCCESS) > 95, "success " + r.value(Metrics.TASK_SUCCESS));
		assertEquals(EDGE_B, flocky.deployments(PA0).get("telemetry").node());
		assertTrue(flocky.hosted(EDGE_A).isEmpty());
	}

	@Test
	void aDefinitionCanProvideTheTraitItself(@TempDir Path out) {
		run(() -> new Flocky().component(Component.of("telemetry").requires("secure-runtime"))
				.definition(ComponentDefinition.of("telemetry-unikernel", "telemetry", 1, 64)
						.withWorkloadType("unikernel").provides("secure-runtime"))
				.workloadTypes(Select.name("edge-b"), "unikernel"), null, out);
		var d = flocky.deployments(PA0).get("telemetry");
		assertEquals(EDGE_B, d.node());
		assertEquals("telemetry-unikernel", d.definition().name());
	}

	@Test
	void definitionsSpreadFromASingleNode(@TempDir Path out) {
		RunResult r = run(() -> new Flocky()
				.definition(ComponentDefinition.of("telemetry-v2", "telemetry", 2, 256), Select.name("pb-2")), null,
				out);
		assertTrue(flocky.repository(PA0).contains("telemetry-v2"));
		assertEquals("telemetry-v2", flocky.deployments(PA0).get("telemetry").definition().name());
		// before the definition reaches a device, its tasks run locally
		assertTrue(r.value(Metrics.TASK_SUCCESS) > 90, "success " + r.value(Metrics.TASK_SUCCESS));
	}

	@Test
	void reservedResourcesLimitTheClientsOfANode(@TempDir Path out) {
		// edge-a has 16 cores: one definition of 10 fits, the second does not
		RunResult r = run(() -> new Flocky().definition(ComponentDefinition.of("x-impl", "x", 10, 64))
				.definition(ComponentDefinition.of("y-impl", "y", 10, 64)).application(Select.name("pa-0"), "x")
				.application(Select.name("pa-1"), "y").deploymentNodes(Select.name("edge-a")), null, out);
		var hosted = flocky.hosted(EDGE_A);
		assertTrue(hosted.containsKey("x-impl") ^ hosted.containsKey("y-impl"), hosted.toString());
		assertTrue(r.data().custom().getOrDefault("flocky.deploy_failures", 0.0) > 0);
	}

	@Test
	void migratesWhenTheDeploymentNodeCrashes(@TempDir Path out) {
		// only edge-b and the cloud attest; the range covers the cloud, which pa-0 learns of first
		RunResult r = run(() -> new Flocky().component(Component.of("telemetry").requires("attestation"))
				.traits(Select.names("edge-b", "cloud"), "attestation").discovery(0.15, 5, 5),
				"30s crash cloud recover 40s", out);
		assertTrue(r.data().custom().getOrDefault("flocky.migrations", 0.0) > 0, r.data().custom().toString());
		var pa0 = r.data().tasks().stream().filter(t -> t.device().equals("pa-0")).toList();
		assertTrue(pa0.stream().anyMatch(t -> t.createdAt() < 30 && "cloud".equals(t.executedOn())),
				"pa-0 used the cloud before the crash");
		assertTrue(pa0.stream().anyMatch(t -> t.createdAt() > 40 && "edge-b".equals(t.executedOn())),
				"pa-0 moved to edge-b after it");
		// a deployment that still holds is not moved back
		assertEquals(EDGE_B, flocky.deployments(PA0).get("telemetry").node());
		assertTrue(r.value(Metrics.TASK_SUCCESS) > 85, "success " + r.value(Metrics.TASK_SUCCESS));
	}

	@Test
	void rejectsTasksThatHaveNoDeployment(@TempDir Path out) {
		RunResult r = run(() -> new Flocky().deploymentNodes(t -> Set.of()).rejectWithoutDeployment(), null, out);
		assertFalse(r.data().tasks().isEmpty());
		assertTrue(r.data().tasks().stream().allMatch(t -> t.outcome() == TaskOutcome.REJECTED));
		assertEquals(0, r.value(Metrics.TASK_SUCCESS), 1e-9);
	}

	@Test
	void aCrashedClientsLeaseExpiresAndFreesItsReservation(@TempDir Path out) {
		// pa-0 deploys x on edge-a, then crash-stops without undeploying
		Map<String, Set<NodeRef>> beforeCrash = new HashMap<>();
		RunResult r = run(() -> new Flocky() {
			@Override
			public void onStart(SdkContext ctx) {
				super.onStart(ctx);
				ctx.schedule(EDGE_A, 35, () -> beforeCrash.putAll(hosted(EDGE_A)));
			}
		}.definition(ComponentDefinition.of("x-impl", "x", 10, 64)).deploymentNodes(Select.name("edge-a"))
				.application(Select.name("pa-0"), "x"), "40s crash pa-0", out);
		assertEquals(Set.of(PA0), beforeCrash.get("x-impl"), beforeCrash.toString());
		var custom = r.data().custom();
		assertTrue(custom.getOrDefault("flocky.leases_expired", 0.0) > 0, custom.toString());
		assertFalse(flocky.hosted(EDGE_A).containsKey("x-impl"), flocky.hosted(EDGE_A).toString());
	}

	@Test
	void aLeaseMustOutlastTheSwirlyPeriod(@TempDir Path out) {
		assertThrows(IllegalArgumentException.class, () -> new Flocky().lease(0));
		var scenario = TestScenarios.twoRegions(1).build();
		assertThrows(Exception.class,
				() -> scenario.run(Candidate.of("flocky", () -> new Flocky().lease(2)), out));
	}

	@Test
	void aRestartDoesNotDuplicateTheServiceLoops(@TempDir Path out) {
		double base = run(Flocky::new, null, out.resolve("base")).data().custom().get("flocky.discovery_rounds");
		double crashed = run(Flocky::new, "20s crash edge-a recover 5s", out.resolve("crash")).data().custom()
				.get("flocky.discovery_rounds");
		// one node down for 5 s loses about one round; duplicated loops would add a round per period
		assertTrue(crashed <= base + 1, "rounds " + crashed + " vs " + base);
		assertTrue(crashed >= base - 3, "rounds " + crashed + " vs " + base);
	}
}
