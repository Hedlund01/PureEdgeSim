package zip.hedlund.pureedgesim.edgesdk.examples;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.NearestByDesign;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.model.ChannelSpec;
import zip.hedlund.pureedgesim.edgesdk.model.ComputeSpec;
import zip.hedlund.pureedgesim.edgesdk.model.EnergySpec;
import zip.hedlund.pureedgesim.edgesdk.model.LossModel;
import zip.hedlund.pureedgesim.edgesdk.model.MemorySpec;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeSpec;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.model.WorkloadSpec;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.CloudPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.EdgePresets;

/**
 * User story 4: define custom device and channel presets by overriding defaults,
 * or from scratch, and use them like the built-in ones.
 */
public final class Story4CustomPresets {

	/** A long-range agricultural drone: a UAV with a bigger battery, slower, and a mesh radio. */
	public static final ChannelSpec MESH_RADIO = ChannelPresets.RF.named("mesh").withBandwidthMbps(2, 2)
			.withRangeM(3_000).withLatencyMs(15).withPacketLoss(0.03);

	public static final NodeSpec AGRI_DRONE = DevicePresets.UAV.named("agri")
			.withEnergy(e -> e.withCapacityWh(250).withPropulsionW(120))
			.withMobility(MobilitySpec.gaussMarkov(3, 8)).withChannel(MESH_RADIO);

	/** A field gateway written from scratch. */
	public static final NodeSpec FIELD_GATEWAY = new NodeSpec("gateway", Tier.EDGE_STATIC, ComputeSpec.of(4, 10_000),
			MemorySpec.gb(8, 256), EnergySpec.battery(2_000, 8, 25), MobilitySpec.STATIC,
			java.util.List.of(MESH_RADIO, ChannelPresets.CELLULAR_4G), true);

	/** A satellite backhaul: high latency, modest bandwidth, no range limit. */
	public static final ChannelSpec SATELLITE = new ChannelSpec("satellite", 20, 100, 300, 0,
			Double.POSITIVE_INFINITY, 0.005, 1500, 5e-7, LossModel.RETRANSMIT, 3);

	public static void main(String[] args) {
		System.out.println("agri drone: " + AGRI_DRONE);
		System.out.println("mesh radio: " + MESH_RADIO);

		Scenario scenario = Scenario.builder("story4-custom-presets").duration(Examples.duration(300)).seed(4)
				.map(4_000, 4_000)
				.addEdgeNode("gateway", FIELD_GATEWAY.withChannel(SATELLITE), Layout.at(2_000, 2_000))
				.addEndDevices(AGRI_DRONE, 6, Layout.near("gateway", 1_500))
				.addCloud("cloud", CloudPresets.REGIONAL_DC.withChannel(SATELLITE), Layout.at(4_000, 4_000))
				.connectEach(Tier.END_DEVICE).toNearest(Tier.EDGE_STATIC).via(MESH_RADIO)
				.connect("gateway", "cloud", SATELLITE)
				.withWorkload(new WorkloadSpec("crop-images", 4, 2_000, 200_000, 1_000, 8, 128)).build();

		RunResult r = scenario.run(Candidate.of("nearest", NearestByDesign::new), Examples.output("story4"));
		System.out.printf("success %.1f %%, mean latency %.0f ms, device energy %.1f Wh%n",
				r.value(zip.hedlund.pureedgesim.edgesdk.metrics.Metrics.TASK_SUCCESS),
				r.value(zip.hedlund.pureedgesim.edgesdk.metrics.Metrics.LATENCY_MEAN),
				r.value(zip.hedlund.pureedgesim.edgesdk.metrics.Metrics.ENERGY_DEVICES));
		// presets stay untouched
		System.out.println("UAV battery is still " + DevicePresets.UAV.energy().capacityWh() + " Wh; truck cores: "
				+ EdgePresets.TRUCK_SERVER.compute().cores());
	}
}
