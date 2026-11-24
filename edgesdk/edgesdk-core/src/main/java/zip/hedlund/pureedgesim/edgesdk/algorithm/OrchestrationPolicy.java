package zip.hedlund.pureedgesim.edgesdk.algorithm;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * A task-offloading algorithm: decides where each task runs (end device, edge or
 * cloud). Decisions are made at the device, with local knowledge only.
 *
 * <pre>{@code
 * public class NearestEdge extends OrchestrationPolicy {
 *     public Placement place(NodeRef device, TaskRequest task) {
 *         return ctx.topology().nearest(device, ctx.topology().edgeNodes())
 *                   .map(Placement::on).orElse(Placement.local());
 *     }
 * }
 * }</pre>
 */
public abstract class OrchestrationPolicy extends Algorithm {

	/**
	 * Decides where the first attempt of a new task runs.
	 *
	 * @param device the device that created the task (where the decision is made)
	 * @param task   the task
	 * @return the placement
	 */
	public abstract Placement place(NodeRef device, TaskRequest task);

	/**
	 * The retry timer of an attempt expired without a result. Return a new
	 * placement to try again (possibly elsewhere), or {@link Placement#reject()}
	 * to stop retrying. Earlier attempts keep running; the first result wins.
	 *
	 * @param device  the device
	 * @param task    the task
	 * @param tried   the node of the attempt that timed out (the device for local;
	 *                for a split, the first part still without a result)
	 * @param attempt the number of attempts so far
	 */
	public Placement onAttemptTimedOut(NodeRef device, TaskRequest task, NodeRef tried, int attempt) {
		return Placement.reject();
	}

	/** The first result of a task reached the device. */
	public void onTaskCompleted(NodeRef device, TaskReport report) {
	}

	/** The deadline of a task passed at the device without a result. No reason is given. */
	public void onTaskTimedOut(NodeRef device, TaskRequest task) {
	}
}
