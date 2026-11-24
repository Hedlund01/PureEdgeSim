package zip.hedlund.pureedgesim.edgesdk.metrics;

import java.util.List;

/**
 * One task of a run.
 *
 * @param id              task id
 * @param device          creating device
 * @param workload        workload name
 * @param createdAt       creation time
 * @param deadline        absolute deadline
 * @param outcome         final outcome (true reason for failures: the first loss of any attempt)
 * @param executedOn      node whose result arrived first, or empty; for a split
 *                        task, the node with the largest share
 * @param completedAt     when the first result arrived, or NaN
 * @param attempts        attempts made by the policy
 * @param duringPartition a partition was active when the task was created
 * @param duringFault     any injected fault was active when the task was created
 * @param parts           every node that executed a part of the winning attempt:
 *                        just {@code executedOn} for a task that was not split,
 *                        empty without a result
 */
public record TaskRecord(long id, String device, String workload, double createdAt, double deadline,
		TaskOutcome outcome, String executedOn, double completedAt, int attempts, boolean duringPartition,
		boolean duringFault, List<String> parts) {

	public TaskRecord {
		executedOn = executedOn == null ? "" : executedOn;
		parts = parts != null ? List.copyOf(parts) : executedOn.isEmpty() ? List.of() : List.of(executedOn);
	}

	/** A task that was not split: its only part is {@code executedOn}. */
	public TaskRecord(long id, String device, String workload, double createdAt, double deadline,
			TaskOutcome outcome, String executedOn, double completedAt, int attempts, boolean duringPartition,
			boolean duringFault) {
		this(id, device, workload, createdAt, deadline, outcome, executedOn, completedAt, attempts, duringPartition,
				duringFault, null);
	}

	/** @return seconds from creation to the first result, or NaN */
	public double latency() {
		return completedAt - createdAt;
	}

	/** @return true when the winning attempt was split over several nodes */
	public boolean split() {
		return parts.size() > 1;
	}
}
