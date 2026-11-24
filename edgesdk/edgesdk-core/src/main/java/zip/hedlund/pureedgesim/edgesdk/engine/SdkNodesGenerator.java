package zip.hedlund.pureedgesim.edgesdk.engine;

import java.util.Random;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.datacentersmanager.ComputingNodesGenerator;
import com.mechalikh.pureedgesim.locationmanager.Location;
import com.mechalikh.pureedgesim.locationmanager.MobilityModel;
import com.mechalikh.pureedgesim.locationmanager.RandomWalkMobilityModel;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;

import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeDecl;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;

/**
 * Creates the PureEdgeSim nodes from the scenario's declarations instead of the
 * XML files.
 */
public class SdkNodesGenerator extends ComputingNodesGenerator {

	public SdkNodesGenerator(SimulationManager simulationManager, Class<? extends MobilityModel> mobilityModelClass,
			Class<? extends ComputingNode> computingNodeClass) {
		super(simulationManager, mobilityModelClass, computingNodeClass);
	}

	@Override
	public void generateDatacentersAndDevices() {
		SdkWorld world = ((SdkSimulationManager) simulationManager).world();
		for (NodeDecl d : world.design().nodes()) {
			SdkNode node = new SdkNode(simulationManager, d, world);
			node.setEnergyModel(new SdkEnergyModel(d.spec().energy()));
			node.setMobilityModel(mobility(d, world));
			node.enableTaskGeneration(false);
			allNodesList.add(node);
			Tier tier = d.tier();
			if (tier == Tier.CLOUD) {
				cloudOnlyList.add(node);
				edgeAndCloudList.add(node);
				mistAndCloudListSensorsExcluded.add(node);
				allNodesListSensorsExcluded.add(node);
			} else if (tier.isEdge()) {
				edgeOnlyList.add(node);
				edgeAndCloudList.add(node);
				mistAndEdgeListSensorsExcluded.add(node);
				allNodesListSensorsExcluded.add(node);
			} else {
				mistOnlyList.add(node);
				mistOnlyListSensorsExcluded.add(node);
				mistAndCloudListSensorsExcluded.add(node);
				mistAndEdgeListSensorsExcluded.add(node);
				allNodesListSensorsExcluded.add(node);
			}
			world.register(node);
		}
	}

	private MobilityModel mobility(NodeDecl d, SdkWorld world) {
		MobilitySpec m = d.spec().mobility();
		Location start = new Location(d.position().x(), d.position().y());
		Class<? extends MobilityModel> type = m.isMobile() ? m.model() : RandomWalkMobilityModel.class;
		MobilityModel model;
		try {
			model = type.getConstructor(SimulationManager.class, Location.class).newInstance(simulationManager, start);
		} catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException(type.getName() + " needs a (SimulationManager, Location) constructor",
					e);
		}
		model.setParameters(m.options());
		model.setMobile(m.isMobile());
		Random r = new Random(world.spec().workloadSeed() ^ ("speed/" + d.name()).hashCode());
		model.setSpeed(m.minSpeed() + r.nextDouble() * (m.maxSpeed() - m.minSpeed()));
		model.setMinPauseDuration(0).setMaxPauseDuration(30).setMinMobilityDuration(30).setMaxMobilityDuration(180);
		return model;
	}
}
