package partitiontestbed;

import java.io.File;
import java.io.IOException;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;

import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.simulationvisualizer.SimulationVisualizer;

/**
 * The live charts of the framework, with the {@link DeploymentChart} added
 * next to them.
 * <p>
 * The framework redraws its charts as fast as the simulation runs, which for
 * this scenario is a second for the whole run. Each redraw is therefore followed
 * by a pause of {@code visualizer.frame_pause_ms} of wall-clock time, so the
 * partitions can be watched as they happen.
 *
 * @see TestbedSimulationManager#startSimulation()
 */
public class TestbedVisualizer extends SimulationVisualizer {

	private final DeploymentChart deploymentChart;
	private final long framePause;

	/**
	 * @param simulationManager the testbed simulation manager
	 * @param deploymentChart   the deployment map to show next to the others
	 */
	public TestbedVisualizer(SimulationManager simulationManager, DeploymentChart deploymentChart) {
		super(simulationManager);
		this.deploymentChart = deploymentChart;
		this.framePause = ((TestbedSimulationManager) simulationManager).getWorld().config.framePause;
		charts.add(deploymentChart);
	}

	@Override
	public void updateCharts() {
		super.updateCharts();
		if (framePause <= 0)
			return;
		try {
			Thread.sleep(framePause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void saveCharts() throws IOException {
		super.saveCharts();
		String folder = SimulationParameters.outputFolder + "/" + simulationManager.getSimulationLogger().getSimStartTime()
				+ "/simulation_" + simulationManager.getSimulationId() + "/iteration_"
				+ simulationManager.getIteration() + "__" + simulationManager.getScenario().toString();
		new File(folder).mkdirs();
		BitmapEncoder.saveBitmapWithDPI(deploymentChart.getChart(), folder + "/deployment", BitmapFormat.PNG,
				300);
	}
}
