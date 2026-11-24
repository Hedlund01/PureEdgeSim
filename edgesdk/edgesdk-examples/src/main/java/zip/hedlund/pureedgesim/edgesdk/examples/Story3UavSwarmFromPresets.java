package zip.hedlund.pureedgesim.edgesdk.examples;

import static zip.hedlund.pureedgesim.edgesdk.fault.FaultScript.at;
import static zip.hedlund.pureedgesim.edgesdk.fault.FaultScript.lasting;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.HeartbeatFallback;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
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
 * User story 3: build a whole system from presets only, with the fluent API: a
 * UAV swarm flying with a truck-mounted edge server, 5G backhaul to two cloud
 * regions, random backhaul failures and a scripted loss of the whole uplink.
 */
public final class Story3UavSwarmFromPresets {

	public static void main(String[] args) {
		// the swarm and its truck follow the same reference point ("convoy")
		MobilitySpec convoy = MobilitySpec.group("convoy", 8);

		Scenario scenario = Scenario.builder("story3-uav-swarm").duration(Examples.duration(600)).seed(3)
				.map(3_000, 3_000)
				.addEdgeNode("truck", EdgePresets.TRUCK_SERVER.withMobility(convoy), Layout.at(1_500, 1_500))
				.addEndDevices(DevicePresets.UAV.withMobility(convoy), 12, Layout.near("truck", 60))
				.addCloud("cloud-eu", CloudPresets.REGIONAL_DC, Layout.at(0, 3_000))
				.addCloud("cloud-us", CloudPresets.HYPERSCALE_DC, Layout.at(3_000, 3_000))
				.connect(Select.tier(Tier.END_DEVICE), Select.name("truck"), ChannelPresets.WIFI)
				.connect(Select.tier(Tier.END_DEVICE), Select.name("truck"), ChannelPresets.RF)
				// cellular backhaul to both clouds; the cloud side has no range limit
				.connect("truck", "cloud-eu", ChannelPresets.CELLULAR_5G.withRangeM(Double.POSITIVE_INFINITY))
				.connect("truck", "cloud-us", ChannelPresets.CELLULAR_4G.withRangeM(Double.POSITIVE_INFINITY))
				.connect("cloud-eu", "cloud-us", ChannelPresets.FIBER)
				.group("swarm", Select.names("truck", "uav-*"))
				.withWorkload(WorkloadPresets.VIDEO_ANALYTICS)
				.withWorkload(WorkloadPresets.CONTROL_LOOP)
				.withFaults(StochasticFaultModel.create()
						.linkFailures(Targets.links().touching(Select.tier(Tier.CLOUD)),
								StochasticFaultModel.exp(240), StochasticFaultModel.exp(20))
						.and(FaultScript.builder().partition(at(300), lasting(60), "swarm", "cloud-eu,cloud-us")
								.build()))
				.build();

		RunResult r = scenario.run(Candidate.of("heartbeat-fallback", HeartbeatFallback::new),
				Examples.output("story3"));
		System.out.printf("tasks: %d, success %.1f %%, success during partition %.1f %%, faults: %d%n",
				r.data().tasks().size(), r.value(Metrics.TASK_SUCCESS), r.value(Metrics.SUCCESS_DURING_PARTITION),
				r.data().faults().records().size());
	}
}
