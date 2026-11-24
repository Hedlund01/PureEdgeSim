package zip.hedlund.pureedgesim.edgesdk.metrics;

/**
 * One task of a run.
 *
 * @param id              task id
 * @param device          creating device
 * @param workload        workload name
 * @param createdAt       creation time
 * @param deadline        absolute deadline
 * @param outcome         final outcome (true reason for failures)
 * @param executedOn      node whose result arrived first, or empty
 * @param completedAt     when the first result arrived, or NaN
 * @param attempts        attempts made by the policy
 * @param duringPartition a partition was active when the task was created
 * @param duringFault     any injected fault was active when the task was created
 */
public record TaskRecord(long id, String device, String workload, double createdAt, double deadline,
		TaskOutcome outcome, String executedOn, double completedAt, int attempts, boolean duringPartition,
		boolean duringFault) {

	/** @return seconds from creation to the first result, or NaN */
	public double latency() {
		return completedAt - createdAt;
	}
}
