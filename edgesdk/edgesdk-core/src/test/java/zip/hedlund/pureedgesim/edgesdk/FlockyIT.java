package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.function.Supplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky.Component;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky.ComponentDefinition;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky.Flocky;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
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
		assertTrue(known.containsAll(java.util.Set.of(EDGE_A, EDGE_B, NodeRef.of("pa-1"))), known.toString());
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
		// only edge-b and the cloud attest; the range covers the cloud
		RunResult r = run(() -> new Flocky().component(Component.of("telemetry").requires("attestation"))
				.traits(Select.names("edge-b", "cloud"), "attestation").discovery(0.15, 5, 5),
				"30s crash edge-b recover 40s", out);
		assertTrue(r.data().custom().getOrDefault("flocky.migrations", 0.0) > 0, r.data().custom().toString());
		assertEquals(NodeRef.of("cloud"), flocky.deployments(PA0).get("telemetry").node());
		assertTrue(r.value(Metrics.TASK_SUCCESS) > 85, "success " + r.value(Metrics.TASK_SUCCESS));
	}
}
