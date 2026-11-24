package zip.hedlund.pureedgesim.edgesdk.presets;

import java.util.List;

import zip.hedlund.pureedgesim.edgesdk.model.ChannelSpec;
import zip.hedlund.pureedgesim.edgesdk.model.LossModel;

/**
 * Default communication channels. Values are order-of-magnitude figures for the
 * effective (not peak) performance of each technology; the reasoning and sources
 * are in {@code docs/edgesdk-presets.md}. Override any field with the
 * {@code with…} methods of {@link ChannelSpec}.
 */
public final class ChannelPresets {

	private static final double INF = Double.POSITIVE_INFINITY;

	private ChannelPresets() {
	}

	/**
	 * LoRa, SF7 / 125 kHz: ~5.5 kbit/s, long range, very low power per second but
	 * high energy per bit because airtime is long.
	 */
	public static final ChannelSpec LORA = new ChannelSpec("lora", 0.0055, 0.0055, 50, 0, 5_000, 0.01, 51, 2.4e-5,
			LossModel.DROP, 0);

	/** Generic narrow-band telemetry or mesh radio (e.g. 900 MHz): 250 kbit/s, ~1 km. */
	public static final ChannelSpec RF = new ChannelSpec("rf", 0.25, 0.25, 10, 0, 1_000, 0.02, 256, 1e-6,
			LossModel.RETRANSMIT, 3);

	/** Wi-Fi 5/6, effective throughput of a shared cell: 100 Mbit/s, ~100 m. */
	public static final ChannelSpec WIFI = new ChannelSpec("wifi", 100, 100, 2, 0, 100, 0.005, 1500, 1e-8,
			LossModel.RETRANSMIT, 3);

	/** LTE: asymmetric 10 / 40 Mbit/s per user, ~30 ms, a 2 km cell. */
	public static final ChannelSpec CELLULAR_4G = new ChannelSpec("4g", 10, 40, 30, 0, 2_000, 0.01, 1500, 1e-7,
			LossModel.RETRANSMIT, 3);

	/** 5G mid-band: 50 / 300 Mbit/s per user, ~10 ms, a 500 m cell. */
	public static final ChannelSpec CELLULAR_5G = new ChannelSpec("5g", 50, 300, 10, 0, 500, 0.001, 1500, 3e-8,
			LossModel.RETRANSMIT, 3);

	/** Gigabit Ethernet LAN: wired, 0.5 ms. */
	public static final ChannelSpec ETHERNET = new ChannelSpec("ethernet", 1_000, 1_000, 0.5, 0, INF, 0.00001, 1500,
			5e-9, LossModel.RETRANSMIT, 3);

	/**
	 * Fibre backhaul to the cloud: 10 Gbit/s, 10 ms of access and routing plus
	 * 0.005 ms per km of distance (light in fibre).
	 */
	public static final ChannelSpec FIBER = new ChannelSpec("fiber", 10_000, 10_000, 10, 0.005, INF, 0.0001, 1500,
			1e-9, LossModel.RETRANSMIT, 3);

	/** @return every preset */
	public static List<ChannelSpec> all() {
		return List.of(LORA, RF, WIFI, CELLULAR_4G, CELLULAR_5G, ETHERNET, FIBER);
	}
}
