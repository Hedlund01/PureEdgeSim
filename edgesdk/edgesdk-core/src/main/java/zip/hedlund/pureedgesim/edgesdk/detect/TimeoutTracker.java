package zip.hedlund.pureedgesim.edgesdk.detect;

/**
 * An adaptive timeout from observed round-trip times (Jacobson/Karels, as in TCP):
 * {@code timeout = srtt + 4 * rttvar}, clamped to {@code [min, max]}.
 */
public final class TimeoutTracker {

	private final double min;
	private final double max;
	private double srtt = Double.NaN;
	private double rttvar;

	public TimeoutTracker(double min, double max) {
		if (!(min > 0) || max < min)
			throw new IllegalArgumentException("need 0 < min <= max");
		this.min = min;
		this.max = max;
	}

	/** Records a measured round trip. */
	public void observe(double rtt) {
		if (Double.isNaN(srtt)) {
			srtt = rtt;
			rttvar = rtt / 2;
		} else {
			rttvar = 0.75 * rttvar + 0.25 * Math.abs(srtt - rtt);
			srtt = 0.875 * srtt + 0.125 * rtt;
		}
	}

	/** Doubles the timeout after a loss (exponential back-off), within the bounds. */
	public void backoff() {
		if (!Double.isNaN(srtt))
			rttvar = Math.min(max, rttvar * 2 + srtt);
	}

	/** @return the current timeout, seconds */
	public double timeout() {
		if (Double.isNaN(srtt))
			return max;
		return Math.max(min, Math.min(max, srtt + 4 * rttvar));
	}

	public double smoothedRtt() {
		return srtt;
	}
}
