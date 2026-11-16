package partitiontestbed;

import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.Task;
import com.mechalikh.pureedgesim.taskorchestrator.Orchestrator;

/**
 * The orchestrator the framework requires. The testbed does not use it: the
 * {@link TestbedSimulationManager} asks the {@link PlacementStrategy} of the run
 * where each service goes, and makes the tasks wait for the deployment, which the
 * framework's orchestrators cannot do.
 */
public class TestbedOrchestrator extends Orchestrator {

	/**
	 * @param simulationManager the testbed simulation manager
	 */
	public TestbedOrchestrator(SimulationManager simulationManager) {
		super(simulationManager);
	}

	/**
	 * Never called: the placements are made by the strategy.
	 *
	 * @return -1, no node
	 */
	@Override
	protected int findComputingNode(String[] architectureLayers, Task task) {
		return -1;
	}

	@Override
	public void resultsReturned(Task task) {
		// The strategy learns about its placements through the testbed, not here.
	}

	@Override
	protected void processEvent(Event e) {
		// Driven by the testbed; it schedules no event of its own.
	}
}
