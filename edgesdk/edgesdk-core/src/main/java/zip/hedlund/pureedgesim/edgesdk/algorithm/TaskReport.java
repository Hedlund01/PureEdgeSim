package zip.hedlund.pureedgesim.edgesdk.algorithm;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * What the device learns when a result arrives.
 *
 * @param task        the task
 * @param executedOn  the node that executed it
 * @param completedAt when the result reached the device
 * @param attempts    how many attempts the policy made
 * @param late        true when it arrived after the deadline was checked (the task counts as failed)
 */
public record TaskReport(TaskRequest task, NodeRef executedOn, double completedAt, int attempts, boolean late) {

	/** @return seconds from creation to the result reaching the device */
	public double latency() {
		return completedAt - task.createdAt();
	}
}
