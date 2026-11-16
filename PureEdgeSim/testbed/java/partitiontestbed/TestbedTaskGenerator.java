package partitiontestbed;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.simulationengine.FutureQueue;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.DefaultTaskGenerator;
import com.mechalikh.pureedgesim.taskgenerator.Task;

/**
 * Makes every device run the application of its type.
 * <p>
 * The default generator deals the applications out to the devices at random, in
 * the shares of {@code usage_percentage}, whatever the devices are: a camera can
 * end up sending telemetry. Here the type of a device decides, through the
 * {@code application} attribute of its {@code <device>} element, so the cameras
 * run video analytics and the sensors send telemetry. {@code usage_percentage}
 * is then ignored; the shares of the device types set the mix.
 *
 * @see TestbedNodesGenerator
 */
public class TestbedTaskGenerator extends DefaultTaskGenerator {

	/**
	 * @param simulationManager the testbed simulation manager
	 */
	public TestbedTaskGenerator(SimulationManager simulationManager) {
		super(simulationManager);
	}

	@Override
	public FutureQueue<Task> generate() {
		simulationTime = SimulationParameters.simulationDuration / 60;
		TestbedWorld world = ((TestbedSimulationManager) simulationManager).getWorld();
		devicesList.removeIf(device -> !device.isGeneratingTasks());
		for (ComputingNode device : devicesList) {
			int application = world.deviceTypeOf(device).application;
			device.setApplicationType(application);
			generateTasksForDevice(device, application);
		}
		return getTaskList();
	}
}
