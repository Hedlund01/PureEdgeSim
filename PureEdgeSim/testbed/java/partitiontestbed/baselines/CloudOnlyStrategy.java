package partitiontestbed.baselines;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

import partitiontestbed.Job;
import partitiontestbed.OrchestrationStrategy;
import partitiontestbed.Service;

/**
 * Sends every task to the cloud, where an instance of every service runs from the
 * start. The edge only relays: a partition that leaves a group without the cloud
 * leaves its devices without service. It bounds what the edge is worth.
 */
public class CloudOnlyStrategy extends OrchestrationStrategy {

	/** The name it is registered under. */
	public static final String NAME = "CLOUD_ONLY";

	@Override
	public void initialize() {
		for (Service service : ctx.services())
			ctx.startInstance(ctx.cloud(), service, null);
	}

	@Override
	public void onTaskArrived(ComputingNode node, Job job) {
		ctx.dispatch(job, node, ctx.cloud());
	}
}
