package partitiontestbed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.distribution.Randomness;
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
 * Hands the tasks of the devices to the strategy under test, while the network
 * breaks under them, and judges them at the end.
 * <p>
 * The framework generates the tasks of every device and runs them on its nodes.
 * Everything in between is the strategy's: when a device creates a task, the
 * {@link TaskEngine} asks the strategy what to do with it, and the strategy sends
 * it, dispatches it to one host or several, deploys instances, exchanges its own
 * messages and decides what to do with results, all over the live network. The
 * framework's own path for a task (orchestrator, offloading destination, results
 * through the orchestrator) is not used.
 * <p>
 * A task is done when its first result reaches its device, or, when it returns
 * nothing, when it first finishes on a host. When the run ends every task is
 * judged against the requirement of its service, so the run ends at
 * {@code simulation_time} whatever is still on its way.
 * <p>
 * A service whose application is a graph of subtasks runs it stage by stage:
 * through the device, or on the edge ({@code services.<n>.pipeline=edge}), where a
 * stage that has run hands its output to the next one at its own host.
 * <p>
 * Every iteration of a sweep draws the same devices and the same tasks: the
 * framework's random stream is restarted from {@code random_seed} and the
 * replication alone, whatever the strategy. This holds for a sweep that runs its
 * scenarios one after the other ({@code parallel_simulation=false}).
 *
 * @see Testbed
 * @see OrchestrationStrategy
 */
public class TestbedSimulationManager extends DefaultSimulationManager implements OnSimulationEndListener {

	/** Event that takes a row of the timeline. */
	private static final int SAMPLE = 301;
	/** Event that makes the network follow what moved. */
	private static final int MOBILITY = 302;

	private final TestbedWorld world;
	private final FailureInjector injector;
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
		world.engine = new TaskEngine(world, this, world.strategy);
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
		world.strategy().initialize();
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
		case SAMPLE:
			world.metrics.sample();
			schedule(this, world.config.sampleInterval, SAMPLE);
			return;
		case MOBILITY:
			if (world.config.serversMove()) {
				// Servers that lost their group join another first, so that the mesh
				// follows the groups as they are now.
				world.injector.transferServers();
				world.creator.updateMesh();
			}
			world.updateAttachments();
			schedule(this, SimulationParameters.updateInterval, MOBILITY);
			return;
		case TRANSFER_RESULTS_TO_ORCH:
			// A node finished running an attempt.
			world.engine.executed((Task) ev.getData());
			return;
		case PRINT_LOG:
			// Every task is judged at the end, done or not: the run ends now.
			tasksCount = simLog.getGeneratedTasks();
			break;
		default:
			break;
		}
		super.processEvent(ev);
	}

	/** A device created a task: the strategy takes it from here. */
	@Override
	protected void sendTaskToOrchestrator(Task task) {
		registerRequest(task);
		simLog.incrementTasksSent();
		world.engine.created(task);
	}

	/** Counts a task that is done, for the progress bar of the framework. */
	void jobDone() {
		tasksCount++;
	}

	/**
	 * The framework fails a task as moved away when its orchestrator and its
	 * destination are two edge data centers further apart than a device's radio
	 * range. Whether a node can be reached is decided by the live topology here.
	 */
	@Override
	protected boolean sameLocation(ComputingNode a, ComputingNode b) {
		if (a.getType() == SimulationParameters.TYPES.EDGE_DATACENTER
				|| b.getType() == SimulationParameters.TYPES.EDGE_DATACENTER)
			return true;
		return super.sameLocation(a, b);
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
		TaskEngine engine = world.engine;
		engine.closeLedger();
		TestbedNetworkModel network = (TestbedNetworkModel) getNetworkModel();
		Map<String, Double> kpis = metrics.summary();
		String name = getClass().getSimpleName();
		SimLog.println("%s - %s, %s, %d devices, %d edge servers: %.0f tasks judged (%.0f censored)", name,
				scenario.getStringOrchAlgorithm(),
				world.config.mixedDelivery() ? "delivery set per service"
						: world.config.returnResults ? "results returned to the device" : "fire and forget",
				scenario.getDevicesCount(), world.servers().size(), kpis.get("tasks"), kpis.get("tasks_censored"));
		SimLog.println("%s -   requirement met %.2f%% (during partitions %.2f%%, otherwise %.2f%%; deadline "
				+ "services %.2f%%, lossless services %.2f%%)", name, kpis.get("requirement_met_pct"),
				kpis.get("requirement_met_partitioned_pct"), kpis.get("requirement_met_healthy_pct"),
				kpis.get("deadline_met_pct"), kpis.get("lossless_met_pct"));
		SimLog.println("%s -   on time %.2f%%, late %.2f%%, lost %.2f%%; delay median %.0f ms, 95th %.0f ms, "
				+ "99th %.0f ms", name, kpis.get("on_time_pct"), kpis.get("late_pct"), kpis.get("lost_pct"),
				kpis.get("delay_p50_ms"), kpis.get("delay_p95_ms"), kpis.get("delay_p99_ms"));
		for (Service service : world.services()) {
			Double met = kpis.get("met_pct[" + service.name.replace(',', ' ') + "]");
			if (met != null)
				SimLog.println("%s -   %-24s %-8s requirement met %6.2f%%%s", name, service.name, service.requirement(),
						met, Double.isNaN(service.targetPct) ? ""
								: String.format(Locale.ROOT, " (target %.1f%%: %s)", service.targetPct,
										met >= service.targetPct ? "reached" : "missed"));
		}
		StringBuilder exposures = new StringBuilder();
		for (Job.Exposure exposure : Job.Exposure.values()) {
			String key = exposure.name().toLowerCase(Locale.ROOT);
			exposures.append(exposures.length() == 0 ? "" : "; ").append(key).append(' ')
					.append(kpis.get("exposed_" + key + "_tasks").intValue()).append(" tasks, ")
					.append(String.format(Locale.ROOT, "%.1f%% met", kpis.get("exposed_" + key + "_met_pct")));
		}
		SimLog.println("%s -   exposed: %s", name, exposures);
		SimLog.println("%s -   cost: %.2f attempts per task, %.1f%% of the runs redundant, %d instances started (%d "
				+ "refused for memory), %.0f MB of instances per server, %.2f kbit/s of control traffic per server; "
				+ "lost on the network: %d messages, %d tasks or results (%d transfers)", name,
				kpis.get("attempts_per_task"), kpis.get("redundant_runs_pct"), engine.instancesStarted,
				engine.instancesRefused, kpis.get("instance_memory_mb_per_server"), kpis.get("control_kbps_per_server"),
				engine.controlLost, engine.dataLost, network.lostTransfers);
		for (String line : world.strategy().reportLines())
			SimLog.println("%s -   %s", name, line);
		SimLog.println("%s -   random failures: %d links, %d cloud uplinks, %d servers (%s)", name,
				injector.linkFailures, injector.wanFailures, injector.nodeFailures,
				world.config.nodeCrash ? "crash" : "isolate");
		SimLog.println("%s -   %d devices attached to another access server; %d servers and devices changed "
				+ "partition group, %d joins refused", name, metrics.handovers, metrics.groupTransfers,
				kpis.get("joins_refused").intValue());
		for (String row : metrics.partitionRows()) {
			String[] cells = row.split(",");
			SimLog.println("%s -   partition %s group %s: %s servers, %s devices, cloud from %s of %s access "
					+ "servers: %s tasks, requirement met %s%%, lost %s%%; servers +%s -%s, devices +%s -%s", name,
					cells[0], cells[3], cells[4], cells[6], cells[8], cells[5], cells[9], cells[10], cells[11],
					cells[12], cells[13], cells[14], cells[15]);
		}
		snapshot("end");
		Testbed.record(scenario.getStringOrchAlgorithm() + "," + scenario.getDevicesCount() + "," + delivery(), kpis);
		String label = label();
		try {
			metrics.write(Path.of(SimulationParameters.outputFolder), label);
			SimLog.println("%s -   wrote %s_*.csv in %s", name, label, SimulationParameters.outputFolder);
		} catch (IOException e) {
			SimLog.println("%s - cannot write the metrics: %s", name, e.getMessage());
		}
	}

	/** @return the tasks of this run */
	List<Job> jobs() {
		return world.engine.jobs();
	}
}
