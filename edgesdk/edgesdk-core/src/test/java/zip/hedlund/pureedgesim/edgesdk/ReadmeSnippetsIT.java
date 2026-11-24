package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.CloudOnly;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.HeartbeatFallback;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Experiment;
import zip.hedlund.pureedgesim.edgesdk.api.ExperimentResult;
import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.fault.NoFaults;
import zip.hedlund.pureedgesim.edgesdk.fault.StochasticFaultModel;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.CloudPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.EdgePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.WorkloadPresets;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageWorkload;
import zip.hedlund.pureedgesim.edgesdk.storage.strategies.EventualGossip;
import zip.hedlund.pureedgesim.edgesdk.storage.strategies.Quorum;

/** The snippets of edgesdk/README.md, compiled and run. */
@Tag("integration")
class ReadmeSnippetsIT {

	public static class MyOffloading extends OrchestrationPolicy {
		@Override
		public Placement place(NodeRef device, TaskRequest task) {
			return ctx.topology().nearest(device, ctx.topology().edgeNodes())
					.map(edge -> Placement.on(edge).withRetryAfter(1.0)).orElse(Placement.local());
		}

		@Override
		public Placement onAttemptTimedOut(NodeRef device, TaskRequest task, NodeRef tried, int attempt) {
			return Placement.local();
		}
	}

	@Test
	void quickStart(@TempDir Path out) throws Exception {
		Scenario scenario = Scenario.builder("uav-swarm").duration(300).seed(42).map(3_000, 3_000)
				.addEdgeNode("truck", EdgePresets.TRUCK_SERVER.withMobility(MobilitySpec.group("convoy", 8)),
						Layout.at(1_500, 1_500))
				.addEndDevices(DevicePresets.UAV.withMobility(MobilitySpec.group("convoy", 8)), 12,
						Layout.near("truck", 60))
				.addCloud("cloud", CloudPresets.REGIONAL_DC, Layout.at(0, 3_000))
				.connect(Select.tier(Tier.END_DEVICE), Select.name("truck"), ChannelPresets.WIFI)
				.connect("truck", "cloud", ChannelPresets.CELLULAR_5G.withRangeM(Double.POSITIVE_INFINITY))
				.group("swarm", Select.names("truck", "uav-*")).withWorkload(WorkloadPresets.VIDEO_ANALYTICS)
				.withStorageWorkload(StorageWorkload.zipf(100, 0.9))
				.withFaults(FaultScript.parse("""
						120s partition swarm | cloud for 60s
						200s crash uav-3 recover 30s
						""")).build();

		RunResult r = scenario.run(Candidate.of("mine", MyOffloading::new), out.resolve("mine"));
		assertTrue(r.value(Metrics.TASK_SUCCESS) > 50, "" + r.value(Metrics.TASK_SUCCESS));

		ExperimentResult result = Experiment.on(scenario).candidate(Candidate.of("cloud-only", CloudOnly::new))
				.candidate(Candidate.of("heartbeat", HeartbeatFallback::new).storage(EventualGossip::new))
				.candidate(Candidate.of("mine", MyOffloading::new).storage(() -> new Quorum(3, 2, 2)))
				.faultModels(NoFaults.INSTANCE, scenario.faultModel(), StochasticFaultModel.links(300, 20))
				.vary("uavs", List.of(0, 4),
						(b, n) -> b.addEndDevices(DevicePresets.UAV.withMobility(MobilitySpec.group("convoy", 8)), n,
								Layout.near("truck", 60)))
				.replications(1).baseline("cloud-only")
				.metrics(Metrics.TASK_SUCCESS, Metrics.SUCCESS_DURING_PARTITION, Metrics.TIME_TO_RECOVER,
						Metrics.STORAGE_AVAILABILITY, Metrics.CONTROL_OVERHEAD)
				.progress(false).run(out.resolve("compare"));
		assertEquals(3 * 3 * 2, result.runs().size());
		assertTrue(Files.exists(out.resolve("compare/charts/task_success_pct.png")));
		assertTrue(result.delta("mine", "cloud-only", Metrics.TASK_SUCCESS) > 0);
	}
}
