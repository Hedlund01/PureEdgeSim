package zip.hedlund.pureedgesim.edgesdk.engine;

import com.mechalikh.pureedgesim.simulationengine.FutureQueue;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.Task;
import com.mechalikh.pureedgesim.taskgenerator.TaskGenerator;

/**
 * Returns no core tasks: the SDK draws its workload itself (see
 * {@link TaskEngine}), from its own seed, so that every candidate gets the same
 * tasks.
 */
public class SdkTaskGenerator extends TaskGenerator {

	public SdkTaskGenerator(SimulationManager simulationManager) {
		super(simulationManager);
	}

	@Override
	public FutureQueue<Task> generate() {
		return new FutureQueue<>();
	}
}
