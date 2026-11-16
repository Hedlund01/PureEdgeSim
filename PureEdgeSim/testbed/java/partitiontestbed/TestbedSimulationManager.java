package partitiontestbed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.distribution.Randomness;
import com.mechalikh.pureedgesim.network.NetworkModel;
import com.mechalikh.pureedgesim.scenariomanager.ApplicationFileParser;
import com.mechalikh.pureedgesim.scenariomanager.Scenario;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationengine.OnSimulationEndListener;
import com.mechalikh.pureedgesim.simulationengine.PureEdgeSim;
import com.mechalikh.pureedgesim.simulationmanager.DefaultSimulationManager;
import com.mechalikh.pureedgesim.simulationmanager.SimLog;
import com.mechalikh.pureedgesim.taskgenerator.Task;

/**
 * Runs the tasks of the devices through the strategy under test, while the
 * network breaks under them.
 * <p>
 * The first task of a device finds its service undeployed. The testbed then asks
 * the strategy to deploy it, from the device's access server, and the task waits
 * the time the strategy says the deployment took: that wait is part of the delay
 * the task is judged on. The tasks the device sends in the meantime queue behind
 * the same deployment. Once deployed, the tasks go straight to the host, until
 * one of them fails or the strategy moves the service; the next one deploys it
 * again.
 * <p>
 * A task either must bring its result back to the device before its deadline
 * ({@code result}), or is done once it has run on its host
 * ({@code fire_and_forget}): {@code tasks.delivery} says which, and
 * {@code services.<n>.delivery} says otherwise for one service, so one run can
 * mix both. Every task that ends is counted, so that the report can tell those
 * that ended while the edge layer was whole from those that ended in each group
 * of a partition.
 * <p>
 * A service whose application is a graph of subtasks can run it as a pipeline on
 * the edge ({@code services.<n>.pipeline=edge}): a stage that has run hands its
 * output to the next stage from its own host, and nothing goes back to the
 * device before the last stage, which follows the delivery mode of the service.
 * <p>
 * The strategy is asked where each task goes
 * ({@link PlacementStrategy#route(Task, TestbedWorld.Placement)}) and told of
 * each task that fails, which is when it decides whether the placement stays
 * ({@link PlacementStrategy#taskFailed(Task, TestbedWorld.Placement)}).
 * <p>
 * Every iteration of a sweep draws the same devices and the same tasks: the
 * framework's random stream is restarted from {@code random_seed} and the
 * replication alone, whatever the strategy. This holds for a sweep that runs its
 * scenarios one after the other ({@code parallel_simulation=false}).
 *
 * @see Testbed
 * @see PlacementStrategy
 */
public class TestbedSimulationManager extends DefaultSimulationManager implements OnSimulationEndListener {

	/** Event that ends a deployment. */
	private static final int DEPLOYMENT_DONE = 300;
	/** Event that takes a row of the timeline. */
	private static final int SAMPLE = 301;
	/** Event that makes the network follow what moved. */
	private static final int MOBILITY = 302;

	private final TestbedWorld world;
	private final FailureInjector injector;
	/** Tasks waiting for the deployment of their device's service. */
	private final Map<ComputingNode, List<Task>> waiting = new HashMap<>();
	/** Time each waiting task started to wait. */
	private final Map<Task, Double> waitingSince = new HashMap<>();
	/** The deployment map, when the charts are shown or saved. */
	private DeploymentChart deploymentChart;

	/**
	 * @param simLog       the simulation logger
	 * @param pureEdgeSim  the simulation engine
	 * @param simulationId the simulation ID
	 * @param iteration    the iteration number
	 * @param scenario     the scenario being simulated, whose orchestration
	 *                     algorithm names the strategy
	 */
	public TestbedSimulationManager(SimLog simLog, PureEdgeSim pureEdgeSim, int simulationId, int iteration,
			Scenario scenario) {
		super(simLog, pureEdgeSim, simulationId, iteration, scenario);
		// The framework gives every scenario of a sweep a random stream of its own.
		// Here every strategy must face the same devices and the same tasks, so the
		// stream depends on the replication only.
		Randomness.beginScenario(1 + Testbed.replication());
		// The applications drew their arrival times from the stream of the scenario
		// before: read again, they draw from this one. Scenarios that run in parallel
		// share them, and keep the framework's behaviour.
		if (!SimulationParameters.parallelism_enabled)
			new ApplicationFileParser(SimulationParameters.applicationFile).parse();
		world = new TestbedWorld(this, new TestbedConfig(Testbed.settingsFile("testbed.properties")),
				Testbed.strategy(scenario.getStringOrchAlgorithm()));
		injector = new FailureInjector(world);
		world.injector = injector;
	}

	/** @return the testbed of this run */
	public TestbedWorld getWorld() {
		return world;
	}

	/**
	 * Lets the strategy set itself up on the finished continuum, then starts the
	 * run, with the deployment map next to the charts of the framework when
	 * {@code display_real_time_charts} is on. With only {@code save_charts} on, the
	 * map is still drawn, for the snapshots of the partitions.
	 */
	@Override
	public void startSimulation() {
		if (world.mobile() && SimulationParameters.updateInterval != Math.rint(SimulationParameters.updateInterval))
			throw new IllegalArgumentException(getClass().getSimpleName() + " - \"update_interval\" must be a whole "
					+ "number of seconds when devices or edge servers move: the framework keeps their positions by "
					+ "the second");
		world.strategy().initialize(world);
		boolean display = SimulationParameters.displayRealTimeCharts && !SimulationParameters.parallelism_enabled;
		if (display || SimulationParameters.saveCharts)
			deploymentChart = new DeploymentChart(this);
		if (display)
			simulationVisualizer = new TestbedVisualizer(this, deploymentChart);
		simLog.print("%s -  %s", getClass().getSimpleName(), scenario.toString());
		simulation.start();
	}

	@Override
	public void onSimulationStart() {
		super.onSimulationStart();
		schedule(this, world.config.sampleInterval, SAMPLE);
		if (world.mobile())
			schedule(this, SimulationParameters.updateInterval, MOBILITY);
	}

	@Override
	public void processEvent(Event ev) {
		switch (ev.getTag()) {
		case DEPLOYMENT_DONE:
			deploymentDone((Deployment) ev.getData());
			return;
		case SAMPLE:
			world.metrics.sample();
			schedule(this, world.config.sampleInterval, SAMPLE);
			return;
		case MOBILITY:
			if (world.config.serversMove())
				world.creator.updateMesh();
			world.updateAttachments();
			schedule(this, SimulationParameters.updateInterval, MOBILITY);
			return;
		case TRANSFER_RESULTS_TO_ORCH:
			Task executed = (Task) ev.getData();
			Service service = world.services().get(executed.getApplicationID());
			// A stage of an edge pipeline hands its output to the next one where it ran.
			boolean handsOver = service.edgePipeline && !executed.getDependents().isEmpty();
			if (handsOver || !service.returnsResults) {
				executedAndForgotten(executed, service.edgePipeline);
				return;
			}
			break;
		case RESULT_RETURN_FINISHED:
			world.metrics.resultsReceived++;
			break;
		default:
			break;
		}
		super.processEvent(ev);
	}

	/**
	 * Ends a task where it ran: the device expects nothing back from it, so it has
	 * succeeded once it has run on its host before its deadline, and no result is
	 * sent.
	 *
	 * @param task   the task that just finished running
	 * @param onEdge whether the tasks that wait for it are sent from its host,
	 *               rather than by the device
	 */
	private void executedAndForgotten(Task task, boolean onEdge) {
		finishedTasks.add(task);
		if (taskFailed(task, 2))
			return;
		simLog.getTasksExecutionInfos(task);
		// The deadline is checked as at the return of a result, on the delay so far.
		if (taskFailed(task, 3))
			return;
		edgeOrchestrator.resultsReturned(task);
		tasksCount++;
		if (onEdge)
			releaseDependentsOnEdge(task);
		else
			releaseDependents(task);
		requestTaskFinished(task, false);
	}

	/**
	 * Sends the stages that waited for a task from the host it ran on, with its
	 * output added to what they carry: the edge pipeline. A stage with several
	 * inputs leaves from the host of the last one to finish.
	 *
	 * @param task the stage that just ran
	 */
	private void releaseDependentsOnEdge(Task task) {
		for (Task next : task.getDependents()) {
			if (isTerminal(next))
				continue;
			next.setFileSizeInBits((long) (next.getFileSizeInBits() + task.getOutputSizeInBits()));
			if (!next.dependencyFinished())
				continue;
			next.setTime(simulation.clock());
			registerRequest(next);
			if (taskFailed(next, 0))
				continue;
			ComputingNode device = next.getEdgeDevice();
			next.setOrchestrator(world.homeOf(device).node);
			simLog.incrementTasksSent();
			sentTasks++;
			TestbedWorld.Placement placement = world.placementOf(device);
			if (!device.isApplicationPlaced() || placement == null) {
				// The service was moved away meanwhile: deploy it again first.
				sendFromOrchToDestination(next);
				continue;
			}
			ComputingNode destination = destination(next, placement);
			next.setOffloadingDestination(destination);
			simLog.taskSentFromOrchToDest(next);
			((TestbedNetworkModel) getNetworkModel()).sendBetweenHosts(task.getOffloadingDestination(), destination,
					next);
		}
	}

	/**
	 * @param task      a task
	 * @param placement where the service of its device runs
	 * @return the node the strategy sends this task to: the host of the placement,
	 *         unless the strategy routes the task elsewhere
	 */
	private ComputingNode destination(Task task, TestbedWorld.Placement placement) {
		ComputingNode routed = world.strategy().route(task, placement);
		return routed == null ? placement.node(world) : routed;
	}

	@Override
	protected void sendFromOrchToDestination(Task task) {
		if (taskFailed(task, 1))
			return;
		ComputingNode device = task.getEdgeDevice();
		TestbedWorld.Placement placement = world.placementOf(device);
		if (device.isApplicationPlaced() && placement != null) {
			forward(task, destination(task, placement));
			return;
		}
		waitingSince.put(task, simulation.clock());
		List<Task> queue = waiting.get(device);
		if (queue != null) {
			queue.add(task);
			return;
		}
		queue = new ArrayList<>();
		queue.add(task);
		waiting.put(device, queue);

		world.release(device);
		Deployment deployment = world.strategy().deploy(device, world.homeOf(device),
				world.services().get(task.getApplicationID()));
		world.metrics.deployment(deployment);
		if (deployment.success())
			world.place(deployment);
		schedule(this, deployment.delay, DEPLOYMENT_DONE, deployment);
	}

	private void deploymentDone(Deployment deployment) {
		ComputingNode device = deployment.device;
		List<Task> queue = waiting.remove(device);
		ComputingNode destination = !deployment.success() ? ComputingNode.NULL
				: deployment.onCloud ? world.cloud() : deployment.host.node;
		if (deployment.success()) {
			device.setApplicationPlacementLocation(destination);
			device.setApplicationPlaced(true);
		}
		// The placement is gone when the device or the strategy moved the service
		// away during the deployment: these tasks still go where it was made.
		TestbedWorld.Placement placement = world.placementOf(device);
		for (Task task : queue) {
			task.addActualNetworkTime(simulation.clock() - waitingSince.remove(task));
			if (!deployment.success()) {
				task.setFailureReason(Task.FailureReason.NO_OFFLOADING_DESTINATIONS);
				simLog.incrementTasksFailedLackOfRessources(task);
				failedTasksCount++;
				tasksCount++;
				failDependents(task);
				requestTaskFinished(task, true);
				continue;
			}
			if (!taskFailed(task, 1))
				forward(task, placement == null ? destination : destination(task, placement));
		}
	}

	private void forward(Task task, ComputingNode destination) {
		task.setOffloadingDestination(destination);
		simLog.taskSentFromOrchToDest(task);
		scheduleNow(getNetworkModel(), NetworkModel.SEND_REQUEST_FROM_ORCH_TO_DESTINATION, task);
	}

	/**
	 * Fails a task whose transfer found no path, or lost the one it had.
	 *
	 * @param task  the task
	 * @param phase 1 when it was lost before it ran, 3 when its result was
	 */
	void failOnNetwork(Task task, int phase) {
		task.setFailureReason(Task.FailureReason.FAILED_DUE_TO_NETWORK_OUTAGE);
		if (phase == 3)
			world.metrics.losingResult(task);
		simLog.incrementTasksFailed(task);
		setFailed(task, phase);
	}

	/**
	 * Fails a task, and lets the strategy decide what becomes of the placement.
	 * <p>
	 * The framework forgets where the application of a device is placed whenever
	 * one of its tasks fails, for any reason, a missed deadline included, so the
	 * next task deploys it again. Here that is the strategy's decision.
	 */
	@Override
	protected boolean setFailed(Task task, int phase) {
		ComputingNode device = task.getEdgeDevice();
		TestbedWorld.Placement placement = world.placementOf(device);
		boolean placed = device.isApplicationPlaced();
		super.setFailed(task, phase);
		if (placement != null && world.placementOf(device) == placement
				&& !world.strategy().taskFailed(task, placement) && placed)
			device.setApplicationPlaced(true);
		return true;
	}

	/**
	 * Lets an access server send work to any edge server it can reach.
	 * <p>
	 * The framework fails a task as moved away when its orchestrator and its
	 * destination are two edge data centers further apart than a device's radio
	 * range, which assumes edge offloading is local. The strategies deploy across
	 * the mesh, and whether the destination can be reached is decided by the live
	 * topology, so two edge servers are always at the same place here.
	 */
	@Override
	protected boolean sameLocation(ComputingNode a, ComputingNode b) {
		// A device and its access server too: the radio link between them is on the
		// live topology, which knows whether the device is in range.
		if (a.getType() == SimulationParameters.TYPES.EDGE_DATACENTER
				|| b.getType() == SimulationParameters.TYPES.EDGE_DATACENTER)
			return true;
		return super.sameLocation(a, b);
	}

	@Override
	protected void requestTaskFinished(Task task, boolean failed) {
		world.metrics.taskFinished(task, failed);
		if (!failed && task.getStatus() != Task.Status.SKIPPED)
			world.strategy().taskCompleted(task, world.placementOf(task.getEdgeDevice()));
		super.requestTaskFinished(task, failed);
	}

	// --------------------------------------------------------------- output

	/** @return what names the files of this iteration */
	private String label() {
		String label = String.format("testbed_%s_%d_devices_%s", scenario.getStringOrchAlgorithm(),
				scenario.getDevicesCount(), delivery());
		return Testbed.replications() > 1 ? label + "_rep" + world.replication : label;
	}

	/** @return the delivery mode of the run, or {@code mixed} when the services differ */
	private String delivery() {
		return world.config.mixedDelivery() ? "mixed" : world.config.returnResults ? "result" : "fire_and_forget";
	}

	/**
	 * Saves the deployment map as it is now, when {@code save_charts} is on.
	 *
	 * @param name what the picture shows, appended to the name of the run
	 */
	void snapshot(String name) {
		if (deploymentChart == null || !SimulationParameters.saveCharts)
			return;
		deploymentChart.update();
		String file = SimulationParameters.outputFolder + File.separator + label() + "_" + name;
		try {
			new File(SimulationParameters.outputFolder).mkdirs();
			BitmapEncoder.saveBitmapWithDPI(deploymentChart.getChart(), file, BitmapFormat.PNG, 200);
		} catch (IOException e) {
			SimLog.println("%s - cannot save %s: %s", getClass().getSimpleName(), file, e.getMessage());
		}
	}

	/**
	 * Prints what happened in this run and writes its files.
	 */
	@Override
	public void onSimulationEnd() {
		TestbedMetrics metrics = world.metrics;
		metrics.sample();
		TestbedNetworkModel network = (TestbedNetworkModel) getNetworkModel();
		String name = getClass().getSimpleName();
		int tasks = metrics.tasksOk + metrics.tasksFailed;
		SimLog.println("%s - %s, %s, %d devices, %d edge servers: %d tasks, %d succeeded, %d failed (%.1f%% "
				+ "succeeded; %.1f%% while the edge layer was whole)", name, scenario.getStringOrchAlgorithm(),
				world.config.mixedDelivery() ? "delivery set per service"
						: world.config.returnResults ? "results returned to the device" : "fire and forget",
				scenario.getDevicesCount(), world.servers().size(), tasks, metrics.tasksOk, metrics.tasksFailed,
				percent(metrics.tasksOk, tasks), percent(metrics.healthyOk, metrics.healthyOk + metrics.healthyFailed));
		SimLog.println("%s -   failed tasks: %d, of which %d while the edge layer was whole and %d during a "
				+ "partition; %d lost on the way to their host, %d lost with their result on the way back; %d results "
				+ "reached their device", name, metrics.tasksFailed, metrics.healthyFailed,
				metrics.tasksFailed - metrics.healthyFailed, metrics.lostOnRequest, metrics.lostOnResult,
				metrics.resultsReceived);
		SimLog.println("%s -   failures by reason: %s (whole layer: %s)", name,
				TestbedMetrics.reasons(metrics.failures), TestbedMetrics.reasons(metrics.healthyFailures));
		for (TestbedWorld.DeviceType type : world.deviceTypes()) {
			TestbedMetrics.TypeRecord record = metrics.byType.getOrDefault(type.name, new TestbedMetrics.TypeRecord());
			int total = record.ok + record.failed;
			SimLog.println("%s -   %-17s -> %-22s %5d tasks, %4d failed (%5.1f%% succeeded), served by edge "
					+ "%5.1f%% / cloud %5.1f%%, delay mean %.0f ms, median %.0f ms, 95th %.0f ms, 99th %.0f ms; "
					+ "failures: %s", name, type.name,
					world.services().get(type.application).name, total, record.failed, percent(record.ok, total),
					percent(record.edge, record.ok), percent(record.cloud, record.ok),
					record.ok == 0 ? Double.NaN : 1000 * record.delay / record.ok,
					1000 * TestbedMetrics.percentile(record.delays, 0.5),
					1000 * TestbedMetrics.percentile(record.delays, 0.95),
					1000 * TestbedMetrics.percentile(record.delays, 0.99), TestbedMetrics.reasons(record.reasons));
		}
		SimLog.println("%s -   %d deployments, %d failed, %d on the cloud, median %.1f ms, 95th percentile %.1f ms; "
				+ "%d transfers lost to a link down", name, metrics.deployments, metrics.deploymentFailures,
				metrics.cloudDeployments, metrics.deploymentLatencyPercentile(0.5) * 1000,
				metrics.deploymentLatencyPercentile(0.95) * 1000, network.lostTransfers);
		for (String line : world.strategy().reportLines())
			SimLog.println("%s -   %s", name, line);
		SimLog.println("%s -   random failures: %d links, %d cloud uplinks, %d servers", name, injector.linkFailures,
				injector.wanFailures, injector.nodeFailures);
		SimLog.println("%s -   %d devices attached to another access server", name, metrics.handovers);
		for (TestbedWorld.ActivePartition partition : world.activePartitions())
			metrics.partitionEnded(partition, simulation.clock());
		for (String row : metrics.partitionRows()) {
			String[] cells = row.split(",");
			int ok = Integer.parseInt(cells[9]);
			int failed = Integer.parseInt(cells[10]);
			SimLog.println("%s -   partition %s group %s: %s servers, %s devices, cloud from %s of %s access "
					+ "servers: %d tasks, %d failed (%.1f%% succeeded), %s deployments (%s failed); failures: %s", name,
					cells[0], cells[3], cells[4], cells[6], cells[8], cells[5], ok + failed, failed,
					percent(ok, ok + failed), cells[11], cells[12], cells[13]);
		}
		snapshot("end");
		Testbed.record(scenario.getStringOrchAlgorithm() + "," + scenario.getDevicesCount() + "," + delivery(),
				metrics.summary());
		String label = label();
		try {
			metrics.write(Path.of(SimulationParameters.outputFolder), label);
			SimLog.println("%s -   wrote %s_*.csv in %s", name, label, SimulationParameters.outputFolder);
		} catch (IOException e) {
			SimLog.println("%s - cannot write the metrics: %s", name, e.getMessage());
		}
	}

	private static double percent(int part, int whole) {
		return whole == 0 ? Double.NaN : 100.0 * part / whole;
	}
}
