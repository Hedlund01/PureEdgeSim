package partitiontestbed.baselines;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

import partitiontestbed.Job;
import partitiontestbed.OrchestrationStrategy;
import partitiontestbed.Service;

/**
 * The simplest strategy there is: every task runs where it arrives, the access
 * server of its device (or, for a stage of an edge pipeline, the host of the
 * stage before it), on an instance started there the first time it is needed.
 * No coordination, no retry, no backup: what the network loses is lost. It is
 * the lower bound of what a strategy that keeps its services local achieves, and
 * the base of {@link RetryStrategy} and {@link ReplicatedStrategy}.
 */
public class AccessServerStrategy extends OrchestrationStrategy {

	/** The name it is registered under. */
	public static final String NAME = "ACCESS_SERVER";

	@Override
	public void initialize() {
		// Nothing to set up: it needs to know nothing about the other servers.
	}

	@Override
	public void onTaskArrived(ComputingNode node, Job job) {
		runHere(node, job);
	}

	/**
	 * Runs a task on the node it is at, starting an instance of its service there
	 * if none runs: the task waits for it to start.
	 *
	 * @param node the node
	 * @param job  the task
	 * @return whether it was dispatched: {@code false} when the node cannot run its
	 *         service, for want of memory or traits
	 */
	protected boolean runHere(ComputingNode node, Job job) {
		Service service = job.service();
		if (!ctx.hasInstance(node, service) && !ctx.startInstance(node, service, null))
			return false;
		ctx.dispatch(job, node, node);
		return true;
	}
}
