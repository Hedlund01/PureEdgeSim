package zip.hedlund.pureedgesim.edgesdk.model;

import java.util.Objects;

/**
 * A communication channel: the technology of a link.
 * <p>
 * A link {@code a → b} uses {@code upMbps} from a to b and {@code downMbps} from b
 * to a. By convention {@code a} is the node nearer to the end devices (a device's
 * uplink to its edge server, an edge server's uplink to the cloud).
 *
 * @param name           unique name, e.g. {@code "wifi"}
 * @param upMbps         bandwidth from a to b, Mbit/s
 * @param downMbps       bandwidth from b to a, Mbit/s
 * @param latencyMs      fixed one-way latency per hop, ms
 * @param latencyPerKmMs extra latency per km of distance (propagation, routing), ms
 * @param rangeM         radio range in metres; {@link Double#POSITIVE_INFINITY} for
 *                       wired channels, which never go out of range
 * @param packetLoss     probability that one packet is lost, in [0, 1)
 * @param packetBytes    packet size used by the loss model
 * @param joulesPerBit   energy per transferred bit, split equally between the two
 *                       endpoints
 * @param lossModel      what loss does to a transfer
 * @param maxRetries     retransmission rounds before giving up
 *                       ({@link LossModel#RETRANSMIT})
 */
public record ChannelSpec(String name, double upMbps, double downMbps, double latencyMs, double latencyPerKmMs,
		double rangeM, double packetLoss, int packetBytes, double joulesPerBit, LossModel lossModel, int maxRetries) {

	public ChannelSpec {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(lossModel, "lossModel");
		if (!(upMbps > 0) || !(downMbps > 0))
			throw new IllegalArgumentException(name + ": bandwidths must be positive");
		if (latencyMs < 0 || latencyPerKmMs < 0)
			throw new IllegalArgumentException(name + ": latencies must be >= 0");
		if (!(rangeM > 0))
			throw new IllegalArgumentException(name + ": range must be positive");
		if (packetLoss < 0 || packetLoss >= 1)
			throw new IllegalArgumentException(name + ": packetLoss must be in [0, 1)");
		if (packetBytes < 1 || maxRetries < 0 || joulesPerBit < 0)
			throw new IllegalArgumentException(name + ": invalid packetBytes, maxRetries or joulesPerBit");
	}

	/** @return true when the channel has a finite radio range */
	public boolean isWireless() {
		return rangeM != Double.POSITIVE_INFINITY;
	}

	public ChannelSpec named(String newName) {
		return new ChannelSpec(newName, upMbps, downMbps, latencyMs, latencyPerKmMs, rangeM, packetLoss, packetBytes,
				joulesPerBit, lossModel, maxRetries);
	}

	public ChannelSpec withBandwidthMbps(double up, double down) {
		return new ChannelSpec(name, up, down, latencyMs, latencyPerKmMs, rangeM, packetLoss, packetBytes,
				joulesPerBit, lossModel, maxRetries);
	}

	public ChannelSpec withLatencyMs(double latency) {
		return new ChannelSpec(name, upMbps, downMbps, latency, latencyPerKmMs, rangeM, packetLoss, packetBytes,
				joulesPerBit, lossModel, maxRetries);
	}

	public ChannelSpec withLatencyPerKmMs(double perKm) {
		return new ChannelSpec(name, upMbps, downMbps, latencyMs, perKm, rangeM, packetLoss, packetBytes,
				joulesPerBit, lossModel, maxRetries);
	}

	public ChannelSpec withRangeM(double range) {
		return new ChannelSpec(name, upMbps, downMbps, latencyMs, latencyPerKmMs, range, packetLoss, packetBytes,
				joulesPerBit, lossModel, maxRetries);
	}

	public ChannelSpec withPacketLoss(double loss) {
		return new ChannelSpec(name, upMbps, downMbps, latencyMs, latencyPerKmMs, rangeM, loss, packetBytes,
				joulesPerBit, lossModel, maxRetries);
	}

	public ChannelSpec withJoulesPerBit(double jpb) {
		return new ChannelSpec(name, upMbps, downMbps, latencyMs, latencyPerKmMs, rangeM, packetLoss, packetBytes,
				jpb, lossModel, maxRetries);
	}

	public ChannelSpec withLossModel(LossModel model, int retries) {
		return new ChannelSpec(name, upMbps, downMbps, latencyMs, latencyPerKmMs, rangeM, packetLoss, packetBytes,
				joulesPerBit, model, retries);
	}
}
