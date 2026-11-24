package zip.hedlund.pureedgesim.edgesdk.presets;

import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.CELLULAR_4G;
import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.CELLULAR_5G;
import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.ETHERNET;
import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.FIBER;
import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.LORA;
import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.RF;
import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.WIFI;

import java.util.List;

import zip.hedlund.pureedgesim.edgesdk.model.ComputeSpec;
import zip.hedlund.pureedgesim.edgesdk.model.EnergySpec;
import zip.hedlund.pureedgesim.edgesdk.model.MemorySpec;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeSpec;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;

/**
 * Default edge nodes, static and mobile. See {@code docs/edgesdk-presets.md}.
 */
public final class EdgePresets {

	private EdgePresets() {
	}

	/** Roadside or on-premises server: 16 cores, 64 GB, mains power. Has a LoRa gateway. */
	public static final NodeSpec ROADSIDE_SERVER = new NodeSpec("roadside", Tier.EDGE_STATIC,
			ComputeSpec.of(16, 25_000), MemorySpec.gb(64, 1_024), EnergySpec.mains(100, 300), MobilitySpec.STATIC,
			List.of(WIFI, CELLULAR_4G, CELLULAR_5G, ETHERNET, FIBER, LORA, RF), true);

	/** Micro data center next to the devices: 64 cores, 256 GB. */
	public static final NodeSpec MICRO_DC = new NodeSpec("microdc", Tier.EDGE_STATIC, ComputeSpec.of(64, 25_000),
			MemorySpec.gb(256, 10_240), EnergySpec.mains(500, 2_000), MobilitySpec.STATIC,
			List.of(WIFI, CELLULAR_4G, CELLULAR_5G, ETHERNET, FIBER), true);

	/**
	 * Truck-mounted server rack: 32 cores, 128 GB, powered by the vehicle, driving
	 * at 5–25 m/s. Carries Wi-Fi and RF for its devices and 4G/5G backhaul.
	 */
	public static final NodeSpec TRUCK_SERVER = new NodeSpec("truck", Tier.EDGE_MOBILE, ComputeSpec.of(32, 25_000),
			MemorySpec.gb(128, 4_096), EnergySpec.mains(200, 800), MobilitySpec.randomWaypoint(5, 25),
			List.of(WIFI, RF, CELLULAR_4G, CELLULAR_5G), true);

	/** Compute in a car or small vehicle: 8 cores, 16 GB, 5–30 m/s. */
	public static final NodeSpec CAR_COMPUTE = new NodeSpec("car", Tier.EDGE_MOBILE, ComputeSpec.of(8, 15_000),
			MemorySpec.gb(16, 256), EnergySpec.mains(30, 150), MobilitySpec.randomWaypoint(5, 30),
			List.of(WIFI, CELLULAR_4G, CELLULAR_5G), true);

	/** @return every preset */
	public static List<NodeSpec> all() {
		return List.of(ROADSIDE_SERVER, MICRO_DC, TRUCK_SERVER, CAR_COMPUTE);
	}
}
