package zip.hedlund.pureedgesim.edgesdk.examples;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.HeartbeatFallback;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Experiment;
import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.fault.NoFaults;
import zip.hedlund.pureedgesim.edgesdk.fault.StochasticFaultModel;
import zip.hedlund.pureedgesim.edgesdk.fault.Targets;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.CloudPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.EdgePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.WorkloadPresets;

/**
 * User story 5: run the same algorithm under several fault models (none,
 * random, scripted partition) and compare the metrics.
 */
public final class Story5CompareFaultModels {

	static Scenario city() {
		var phone = DevicePresets.SMARTPHONE.withMobility(MobilitySpec.STATIC);
		return Scenario.builder("story5-city").duration(Examples.duration(400)).seed(5).map(2_000, 2_000)
				.addEdgeNode("rsu-1", EdgePresets.ROADSIDE_SERVER, Layout.at(500, 500))
				.addEdgeNode("rsu-2", EdgePresets.ROADSIDE_SERVER, Layout.at(1_500, 500))
				.addEdgeNode("mdc", EdgePresets.MICRO_DC, Layout.at(1_000, 1_500))
				.addCloud("cloud", CloudPresets.REGIONAL_DC, Layout.at(1_000, 2_000))
				.addEndDevices(phone, 8, Layout.near("rsu-1", 150)).addEndDevices(phone, 8, Layout.near("rsu-2", 150))
				.connectEach(Tier.END_DEVICE).toNearest(Select.name("rsu-*")).via(ChannelPresets.CELLULAR_5G)
				.connect(Select.name("rsu-*"), Select.name("mdc"), ChannelPresets.ETHERNET)
				.connect("rsu-1", "rsu-2", ChannelPresets.ETHERNET).connect("mdc", "cloud", ChannelPresets.FIBER)
				.group("district-1", Select.names("rsu-1", "phone-0", "phone-1", "phone-2", "phone-3", "phone-4",
						"phone-5", "phone-6", "phone-7"))
				.withWorkload(WorkloadPresets.VIDEO_ANALYTICS).build();
	}

	public static void main(String[] args) {
		Experiment.on(city()).candidate(Candidate.of("heartbeat-fallback", HeartbeatFallback::new))
				.faultModels(NoFaults.INSTANCE,
						StochasticFaultModel.create()
								.linkFailures(Targets.links().channel(ChannelPresets.ETHERNET),
										StochasticFaultModel.exp(120), StochasticFaultModel.exp(15))
								.nodeCrashes(Targets.nodes(Tier.EDGE_STATIC), StochasticFaultModel.exp(600),
										StochasticFaultModel.exp(30))
								.named("random"),
						FaultScript.parse("120s partition district-1 for 90s").labelledScript("partition"))
				.replications(Examples.replications(3))
				.metrics(Metrics.TASK_SUCCESS, Metrics.SUCCESS_DURING_PARTITION, Metrics.TASKS_LOST,
						Metrics.LATENCY_P95, Metrics.TIME_TO_RECOVER, Metrics.DETECTION_LATENCY,
						Metrics.FALSE_SUSPICIONS)
				.run(Examples.output("story5"));
	}
}
