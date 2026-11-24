package zip.hedlund.pureedgesim.edgesdk.network;

/**
 * A degraded link: more latency, more loss, less bandwidth. Degradations that
 * overlap combine: latencies add, losses compound, bandwidth factors multiply.
 *
 * @param extraLatencyS   added one-way latency, seconds
 * @param extraLoss       added packet loss probability, in [0, 1)
 * @param bandwidthFactor multiplier of the bandwidth, in (0, 1]
 */
public record Degradation(double extraLatencyS, double extraLoss, double bandwidthFactor) {

	public static final Degradation NONE = new Degradation(0, 0, 1);

	public Degradation {
		if (extraLatencyS < 0 || extraLoss < 0 || extraLoss >= 1 || !(bandwidthFactor > 0) || bandwidthFactor > 1)
			throw new IllegalArgumentException("invalid degradation " + extraLatencyS + ", " + extraLoss + ", "
					+ bandwidthFactor);
	}

	public static Degradation latency(double seconds) {
		return new Degradation(seconds, 0, 1);
	}

	public static Degradation loss(double probability) {
		return new Degradation(0, probability, 1);
	}

	public static Degradation bandwidth(double factor) {
		return new Degradation(0, 0, factor);
	}

	public Degradation plusLatency(double seconds) {
		return new Degradation(extraLatencyS + seconds, extraLoss, bandwidthFactor);
	}

	public Degradation plusLoss(double probability) {
		return combine(loss(probability));
	}

	public Degradation timesBandwidth(double factor) {
		return new Degradation(extraLatencyS, extraLoss, bandwidthFactor * factor);
	}

	/** @return the effect of both degradations at once */
	public Degradation combine(Degradation o) {
		return new Degradation(extraLatencyS + o.extraLatencyS, 1 - (1 - extraLoss) * (1 - o.extraLoss),
				bandwidthFactor * o.bandwidthFactor);
	}
}
