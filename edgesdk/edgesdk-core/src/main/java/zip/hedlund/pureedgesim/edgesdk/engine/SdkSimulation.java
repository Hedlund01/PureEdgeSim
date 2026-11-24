package zip.hedlund.pureedgesim.edgesdk.engine;

import com.mechalikh.pureedgesim.scenariomanager.Scenario;
import com.mechalikh.pureedgesim.simulationmanager.Simulation;

/**
 * A PureEdgeSim {@link Simulation} wired to the SDK classes. It skips the XML
 * files (the parameters are already set by {@link ParametersBridge}) and runs a
 * single scenario.
 */
public class SdkSimulation extends Simulation {

	private final int devices;

	public SdkSimulation(int devices) {
		this.devices = devices;
		setCustomSimulationManager(SdkSimulationManager.class);
		setCustomComputingNode(SdkNode.class);
		setCustomComputingNodesGenerator(SdkNodesGenerator.class);
		setCustomTopologyCreator(SdkTopologyCreator.class);
		setCustomNetworkModel(SdkNetworkModel.class);
		setCustomTaskGenerator(SdkTaskGenerator.class);
		setCustomEdgeOrchestrator(SdkOrchestrator.class);
	}

	@Override
	protected boolean checkFiles() {
		return true;
	}

	@Override
	protected void loadScenarios() {
		iterations.add(new Scenario(devices, 0, 0));
	}
}
