package zip.hedlund.pureedgesim.edgesdk.engine;

import com.mechalikh.pureedgesim.scenariomanager.Scenario;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationengine.OnSimulationEndListener;
import com.mechalikh.pureedgesim.simulationengine.OnSimulationStartListener;
import com.mechalikh.pureedgesim.simulationengine.PureEdgeSim;
import com.mechalikh.pureedgesim.simulationmanager.SimLog;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;

/**
 * The PureEdgeSim simulation manager of an SDK run. It replaces the core task
 * life cycle with the SDK's ({@link TaskEngine}), starts the world at time 0 and
 * stops the run at the scenario's duration.
 */
public class SdkSimulationManager extends SimulationManager
		implements OnSimulationStartListener, OnSimulationEndListener {

	private static final int BOOT = 1001;
	private static final int END = 1002;

	private final SdkWorld world;

	public SdkSimulationManager(SimLog simLog, PureEdgeSim pureEdgeSim, int simulationId, int iteration,
			Scenario scenario) {
		super(simLog, pureEdgeSim, simulationId, iteration, scenario);
		this.world = new SdkWorld(this, RunContext.current());
	}

	public SdkWorld world() {
		return world;
	}

	@Override
	public void startSimulation() {
		simulation.start();
	}

	@Override
	public void onSimulationStart() {
		// after every node's own start (paths are generated in their listeners)
		schedule(this, 0.0, BOOT);
	}

	@Override
	public void processEvent(Event e) {
		switch (e.getTag()) {
		case BOOT -> world.guard(() -> {
			world.start();
			schedule(this, world.duration(), END);
		});
		case END -> simulation.terminate();
		case TRANSFER_RESULTS_TO_ORCH -> world.guard(() -> world.tasks().onExecuted((SdkTask) e.getData()));
		default -> {
			// no other core events are used
		}
		}
	}

	@Override
	public void onSimulationEnd() {
		world.guard(world::finish);
	}

	@Override
	public double getFailureRate() {
		return world.tasks() == null ? 0 : world.tasks().failurePct();
	}
}
