package zip.hedlund.pureedgesim.edgesdk.examples;

import java.util.EnumSet;
import java.util.Set;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.Mvtora;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.Mvtora.Mode;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Experiment;
import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.fault.NoFaults;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.model.ComputeSpec;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.EdgePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.WorkloadPresets;

/**
 * MVTORA (Sun et al., arXiv:2309.16709) in a post-disaster rescue area: client
 * UAVs fly over a disaster area, an edge UAV hovers at its centre with
 * 30 000 MIPS of MEC resources, and rescue vehicles lend their idle computing
 * as fog nodes. MVTORA is compared with the paper's ELC, EMC, VTO and MTO
 * baselines, which are MVTORA restricted to some offloading modes.
 */
public final class Story9MvtoraPostDisasterRescue {

	public static void main(String[] args) {
		// the E-UAV hovers: a static edge node with 30 GHz-class MEC resources
		var euav = EdgePresets.ROADSIDE_SERVER.withCompute(c -> ComputeSpec.of(4, 7_500));
		var vehicle = EdgePresets.CAR_COMPUTE.withMobility(MobilitySpec.randomWaypoint(5, 15));
		var cuav = DevicePresets.UAV.withMobility(MobilitySpec.gaussMarkov(5, 15));

		Scenario scenario = Scenario.builder("story9-mvtora").duration(Examples.duration(300)).seed(9)
				.map(2_000, 2_000)
				.addEdgeNode("euav", euav, Layout.at(1_000, 1_000))
				.addEdgeNodes(vehicle, 20, Layout.near("euav", 300))
				.addEndDevices(cuav, 15, Layout.near("euav", 300))
				.connect(Select.tier(Tier.END_DEVICE), Select.name("euav"), ChannelPresets.CELLULAR_5G)
				.connect(Select.name("car-*"), Select.name("euav"), ChannelPresets.CELLULAR_5G)
				.connect(Select.tier(Tier.END_DEVICE), Select.name("car-*"), ChannelPresets.WIFI)
				.withWorkload(WorkloadPresets.VIDEO_ANALYTICS.withRatePerMinute(40))
				.withFaults(NoFaults.INSTANCE).build();

		Experiment.on(scenario)
				.candidate(mvtora("mvtora", EnumSet.allOf(Mode.class)))
				.candidate(mvtora("elc", EnumSet.of(Mode.LOCAL)))
				.candidate(mvtora("emc", EnumSet.of(Mode.MEC)))
				.candidate(mvtora("vto", EnumSet.of(Mode.LOCAL, Mode.VFC)))
				.candidate(mvtora("mto", EnumSet.of(Mode.LOCAL, Mode.MEC)))
				.baseline("elc").replications(Examples.replications(3))
				.metrics(Metrics.custom("mvtora.utility.mean"), Metrics.TASK_SUCCESS, Metrics.LATENCY_P95,
						Metrics.ENERGY_DEVICES)
				.run(Examples.output("story9"));
	}

	private static Candidate mvtora(String name, Set<Mode> modes) {
		return Candidate.of(name,
				() -> new Mvtora(Mvtora.Params.defaults(), Select.name("euav"), Select.name("car-*"), modes));
	}
}
