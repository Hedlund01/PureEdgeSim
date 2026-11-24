package zip.hedlund.pureedgesim.edgesdk.algorithm.policies;

import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * Runs every task on its own device when a core is free, otherwise offloads it
 * to the nearest edge node by design. A baseline that needs no network when the
 * device is idle.
 */
public class LocalFirst extends OrchestrationPolicy {

	@Override
	public Placement place(NodeRef device, TaskRequest task) {
		if (ctx.local(device).busyCores() < ctx.local(device).cores())
			return Placement.local();
		return ctx.topology().nearest(device, ctx.topology().edgeNodes()).map(Placement::on)
				.orElse(Placement.local());
	}
}
