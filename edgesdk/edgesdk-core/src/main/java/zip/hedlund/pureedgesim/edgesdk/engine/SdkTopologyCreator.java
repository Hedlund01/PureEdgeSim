package zip.hedlund.pureedgesim.edgesdk.engine;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode.LinkOrientation;
import com.mechalikh.pureedgesim.datacentersmanager.ComputingNodesGenerator;
import com.mechalikh.pureedgesim.datacentersmanager.TopologyCreator;
import com.mechalikh.pureedgesim.network.NetworkLink;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;

import zip.hedlund.pureedgesim.edgesdk.model.LinkSpec;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.network.SdkLink;
import zip.hedlund.pureedgesim.edgesdk.network.SdkNetworkLink;

/**
 * Builds the links of the scenario, in the core infrastructure graph and in the
 * SDK live network. A device's first link is also set as its PureEdgeSim
 * up/down link, so that core code that looks for one finds it.
 */
public class SdkTopologyCreator extends TopologyCreator {

	public SdkTopologyCreator(SimulationManager simulationManager, ComputingNodesGenerator computingNodesGenerator) {
		super(simulationManager, computingNodesGenerator);
	}

	@Override
	public void generateTopologyGraph() {
		SdkWorld world = ((SdkSimulationManager) simulationManager).world();
		for (LinkSpec spec : world.design().links()) {
			SdkNode a = world.node(spec.a());
			SdkNode b = world.node(spec.b());
			SdkLink link = new SdkLink(spec, a, b, world.live());
			SdkNetworkLink ab = new SdkNetworkLink(a, b, link, true, simulationManager, world.network());
			SdkNetworkLink ba = new SdkNetworkLink(b, a, link, false, simulationManager, world.network());
			link.attach(ab, ba);
			infrastructureTopology.addLink(ab);
			infrastructureTopology.addLink(ba);
			world.live().add(link);
			primary(a, ab, ba);
			primary(b, ba, ab);
		}
	}

	private static void primary(SdkNode node, SdkNetworkLink up, SdkNetworkLink down) {
		if (node.decl().tier() != Tier.END_DEVICE || node.getCurrentLink(LinkOrientation.UP_LINK) != NetworkLink.NULL)
			return;
		node.setCurrentLink(up, LinkOrientation.UP_LINK);
		node.setCurrentLink(down, LinkOrientation.DOWN_LINK);
	}
}
