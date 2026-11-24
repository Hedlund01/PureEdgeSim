package zip.hedlund.pureedgesim.edgesdk.algorithm.policies;

import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;

/** Offloads every task to the nearest cloud data center (by design latency). */
public class CloudOnly extends OrchestrationPolicy {

	@Override
	public Placement place(NodeRef device, TaskRequest task) {
		return ctx.topology().nearest(device, ctx.topology().nodes(Tier.CLOUD)).map(Placement::on)
				.orElse(Placement.reject());
	}
}
