package zip.hedlund.pureedgesim.edgesdk.presets;

import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.CELLULAR_4G;
import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.CELLULAR_5G;
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
 * Default end devices. See {@code docs/edgesdk-presets.md} for the reasoning
 * behind each value.
 */
public final class DevicePresets {

	private DevicePresets() {
	}

	/**
	 * Quadcopter with a Jetson-Nano-class companion computer, a 77 Wh battery
	 * (Mavic-3 class), Gauss-Markov flight at 5–15 m/s. Propulsion is not drained
	 * by default; add it with {@code withEnergy(e -> e.withPropulsionW(150))}.
	 */
	public static final NodeSpec UAV = new NodeSpec("uav", Tier.END_DEVICE, ComputeSpec.of(4, 4_000),
			MemorySpec.gb(4, 32), EnergySpec.battery(77, 5, 15), MobilitySpec.gaussMarkov(5, 15),
			List.of(WIFI, RF, CELLULAR_5G), false);

	/** Ground robot with a Xavier-NX-class computer and a 500 Wh pack, 1–3 m/s. */
	public static final NodeSpec UGV = new NodeSpec("ugv", Tier.END_DEVICE, ComputeSpec.of(6, 6_000),
			MemorySpec.gb(8, 64), EnergySpec.battery(500, 10, 30), MobilitySpec.randomWaypoint(1, 3),
			List.of(WIFI, RF, CELLULAR_4G), false);

	/** Static, low-power sensor on a Cortex-M-class MCU with two AA cells, LoRa only. */
	public static final NodeSpec IOT_SENSOR = new NodeSpec("sensor", Tier.END_DEVICE, ComputeSpec.of(1, 100),
			MemorySpec.of(0.25, 4), EnergySpec.battery(7.5, 0.01, 0.1), MobilitySpec.STATIC, List.of(LORA), false);

	/** Smartphone: 8 cores, 15 Wh battery, pedestrian random walk at 0.5–2 m/s. */
	public static final NodeSpec SMARTPHONE = new NodeSpec("phone", Tier.END_DEVICE, ComputeSpec.of(8, 8_000),
			MemorySpec.gb(8, 128), EnergySpec.battery(15, 0.5, 5), MobilitySpec.randomWalk(0.5, 2),
			List.of(WIFI, CELLULAR_4G, CELLULAR_5G), false);

	/** Smartwatch-class wearable: 1.2 Wh battery, Wi-Fi only (BLE is not modelled). */
	public static final NodeSpec WEARABLE = new NodeSpec("wearable", Tier.END_DEVICE, ComputeSpec.of(2, 1_000),
			MemorySpec.gb(1, 8), EnergySpec.battery(1.2, 0.05, 0.5), MobilitySpec.randomWalk(0.5, 2), List.of(WIFI),
			false);

	/** @return every preset */
	public static List<NodeSpec> all() {
		return List.of(UAV, UGV, IOT_SENSOR, SMARTPHONE, WEARABLE);
	}
}
