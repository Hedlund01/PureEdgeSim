package zip.hedlund.pureedgesim.edgesdk.algorithm;

import java.util.List;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * What the device learns when a result arrives.
 *
 * @param task        the task
 * @param executedOn  the node that executed it; for a {@link Placement#split
 *                    split}, the node with the largest share
 * @param completedAt when the result reached the device (for a split, the last
 *                    part's result)
 * @param attempts    how many attempts the policy made
 * @param late        true when it arrived after the deadline was checked (the task counts as failed)
 * @param parts       every node that executed a part of the winning attempt;
 *                    just {@code executedOn} for a task that was not split
 */
public record TaskReport(TaskRequest task, NodeRef executedOn, double completedAt, int attempts, boolean late,
		List<NodeRef> parts) {

	public TaskReport {
		parts = parts != null ? List.copyOf(parts) : List.of(executedOn);
	}

	/** A result of a task that was not split: its only part is {@code executedOn}. */
	public TaskReport(TaskRequest task, NodeRef executedOn, double completedAt, int attempts, boolean late) {
		this(task, executedOn, completedAt, attempts, late, null);
	}

	/** @return seconds from creation to the result reaching the device */
	public double latency() {
		return completedAt - task.createdAt();
	}
}
