package zip.hedlund.pureedgesim.edgesdk.metrics;

/**
 * What finally happened to a task, with the true reason of a failure. Recorded
 * for the metrics only; the algorithm never learns the reason.
 */
public enum TaskOutcome {
	/** The result reached the device before the deadline. */
	SUCCESS,
	/** The result reached the device after the deadline. */
	LATE,
	/** The policy rejected the task. */
	REJECTED,
	/** No path to the target when a transfer was sent. */
	NO_ROUTE,
	/** A link of the path went down while the data was on it. */
	LINK_LOST,
	/** Lost to packet loss (after retransmissions). */
	PACKET_LOSS,
	/** The executing node crashed while holding the task. */
	NODE_CRASHED,
	/** The device was down when the task was due, or crashed while waiting. */
	DEVICE_DOWN,
	/** Nothing was lost but the result did not come back in time (queueing, slow links). */
	TIMED_OUT;

	/** @return true for {@link #SUCCESS} */
	public boolean succeeded() {
		return this == SUCCESS;
	}
}
