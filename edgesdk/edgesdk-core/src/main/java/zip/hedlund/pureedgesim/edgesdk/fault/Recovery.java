package zip.hedlund.pureedgesim.edgesdk.fault;

/**
 * What happens after a crash: the node restarts after a delay, or never.
 *
 * @param after seconds until restart; infinity for crash-stop
 */
public record Recovery(double after) {

	public Recovery {
		if (!(after > 0))
			throw new IllegalArgumentException("recovery delay must be positive");
	}

	/** Crash-stop: the node never comes back. */
	public static Recovery never() {
		return new Recovery(Double.POSITIVE_INFINITY);
	}

	/** Crash-recovery: the node restarts after {@code seconds}. */
	public static Recovery after(double seconds) {
		return new Recovery(seconds);
	}

	public boolean recovers() {
		return after != Double.POSITIVE_INFINITY;
	}
}
