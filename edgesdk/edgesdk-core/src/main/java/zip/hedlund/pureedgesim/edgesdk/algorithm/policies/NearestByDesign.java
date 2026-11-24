package zip.hedlund.pureedgesim.edgesdk.algorithm.policies;

import java.util.ArrayList;
import java.util.List;

import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;

/**
 * Offloads every task to the server with the lowest design latency from the
 * device (edge first, cloud otherwise). It never notices faults: the
 * fault-oblivious baseline.
 */
public class NearestByDesign extends OrchestrationPolicy {

	@Override
	public Placement place(NodeRef device, TaskRequest task) {
		List<NodeRef> servers = new ArrayList<>(ctx.topology().edgeNodes());
		servers.addAll(ctx.topology().nodes(Tier.CLOUD));
		return ctx.topology().nearest(device, servers).map(Placement::on).orElse(Placement.local());
	}
}
