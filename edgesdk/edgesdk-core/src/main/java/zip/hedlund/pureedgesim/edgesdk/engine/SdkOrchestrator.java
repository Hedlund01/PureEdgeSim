package zip.hedlund.pureedgesim.edgesdk.engine;

import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.Task;
import com.mechalikh.pureedgesim.taskorchestrator.Orchestrator;

/**
 * Placeholder orchestrator: placement is decided by the candidate's
 * {@link zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy}, called by
 * the SDK task engine at the device.
 */
public class SdkOrchestrator extends Orchestrator {

	public SdkOrchestrator(SimulationManager simulationManager) {
		super(simulationManager);
	}

	@Override
	public void initialize() {
		// no candidate list: the policy decides
	}

	@Override
	protected int findComputingNode(String[] architectureLayers, Task task) {
		return -1;
	}

	@Override
	public void resultsReturned(Task task) {
		// results are handled by the SDK task engine
	}

	@Override
	protected void processEvent(com.mechalikh.pureedgesim.simulationengine.Event e) {
		// unused
	}
}
