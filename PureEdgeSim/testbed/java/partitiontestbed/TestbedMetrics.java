package partitiontestbed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters.TYPES;
import com.mechalikh.pureedgesim.taskgenerator.Task;

/**
 * What the testbed measures, whatever the strategy, and the files it writes.
 * <p>
 * Five files per run, in the output folder, named after the algorithm, the
 * number of devices and the delivery mode of the iteration:
 * <ul>
 * <li>{@code *_timeline.csv}: one row every {@code metrics.sample_interval_s},
 * with the partitions in progress, the links down, the tasks that succeeded and
 * failed in the interval, the deployments so far, and the strategy's own
 * columns.</li>
 * <li>{@code *_failures.csv}: one row per failed task, with its device, the
 * server it is attached to, its partition group, why it failed, and whether it
 * was lost on its way to its host or with its result on the way back.</li>
 * <li>{@code *_device_types.csv}: one row per type of end device, with its
 * tasks, where they ran, and why they failed.</li>
 * <li>{@code *_partitions.csv}: one row per group of every partition, with what
 * the group held and what happened to its tasks and deployments while it was
 * cut.</li>
 * <li>{@code *_deployments.csv}: one row per deployment, with the strategy's own
 * columns.</li>
 * </ul>
 *
 * @see TestbedSimulationManager#onSimulationEnd()
 */
public class TestbedMetrics {

	/** One group of one partition. */
	public static final class GroupRecord {
		/** Index of the group in its partition. */
		public final int index;
		/** Names of its servers. */
		public final List<String> nodes;
		/** Access servers it holds. */
		public final int accessNodes;
		/** Devices attached to its servers. */
		public final int devices;
		/** Gateways it holds. */
		public final int gateways;
		/**
		 * Access servers that could reach the cloud when the partition started. A
		 * group is not always connected inside: the groups of {@code random:k} rarely
		 * are, so one of its servers reaching the cloud says little about the others.
		 */
		public final int accessNodesWithCloud;
		/** Devices of each type attached to its servers, for example {@code IoT sensor:12}. */
		public final String deviceMix;
		int tasksOk;
		int tasksFailed;
		/** Failed tasks of its devices while it was cut, by failure reason. */
		final Map<String, Integer> failureReasons = new TreeMap<>();
		int deployments;
		int deploymentFailures;

		GroupRecord(int index, List<String> nodes, int accessNodes, int devices, int gateways,
				int accessNodesWithCloud, String deviceMix) {
			this.index = index;
			this.nodes = nodes;
			this.accessNodes = accessNodes;
			this.devices = devices;
			this.gateways = gateways;
			this.accessNodesWithCloud = accessNodesWithCloud;
			this.deviceMix = deviceMix;
		}
	}

	/** What happened to the tasks of one type of device. */
	static final class TypeRecord {
		int ok;
		int failed;
		int edge;
		int cloud;
		double delay;
		/** Delay of every task that succeeded, in seconds. */
		final List<Double> delays = new ArrayList<>();
		final Map<String, Integer> reasons = new TreeMap<>();
	}

	private final TestbedWorld world;

	int tasksOk;
	int tasksFailed;
	int healthyOk;
	int healthyFailed;
	private int windowOk;
	private int windowFailed;
	/** Failed tasks by failure reason, over the whole run. */
	final Map<String, Integer> failures = new TreeMap<>();
	/** Failed tasks by failure reason, while no partition was in progress. */
	final Map<String, Integer> healthyFailures = new TreeMap<>();
	/** Failed tasks lost on their way to their host, and on the way back with their result. */
	int lostOnRequest;
	int lostOnResult;
	/** Results that reached the device that sent the task, on time or not. */
	int resultsReceived;
	/** Times a device attached to another access server. */
	int handovers;
	/** Delay of every task that succeeded, in seconds. */
	private final List<Double> delays = new ArrayList<>();
	/** Tasks by device type, in the order the types first finished a task. */
	final Map<String, TypeRecord> byType = new LinkedHashMap<>();
	/** Tasks whose result was lost on the way back, until they are recorded. */
	private final Set<Task> losingResult = Collections.newSetFromMap(new IdentityHashMap<>());

	int deployments;
	int deploymentFailures;
	int cloudDeployments;
	private final List<Double> deploymentLatencies = new ArrayList<>();

	private final List<String> timeline = new ArrayList<>();
	private final List<String> failureLog = new ArrayList<>();
	private final List<String> deploymentLog = new ArrayList<>();
	private final List<String> partitionLog = new ArrayList<>();
	private double lastSample = -1;

	TestbedMetrics(TestbedWorld world) {
		this.world = world;
	}

	// ---------------------------------------------------------------- tasks

	/**
	 * Records a task that reached a terminal state.
	 *
	 * @param task   the task
	 * @param failed whether it failed
	 */
	void taskFinished(Task task, boolean failed) {
		String reason = String.valueOf(task.getFailureReason());
		TestbedWorld.DeviceType type = world.deviceTypeOf(task.getEdgeDevice());
		if (type != null) {
			TypeRecord record = byType.computeIfAbsent(type.name, name -> new TypeRecord());
			if (failed) {
				record.failed++;
				record.reasons.merge(reason, 1, Integer::sum);
			} else {
				record.ok++;
				record.delay += task.getTotalDelay();
				record.delays.add(task.getTotalDelay());
				delays.add(task.getTotalDelay());
				if (task.getOffloadingDestination().getType() == TYPES.CLOUD)
					record.cloud++;
				else if (task.getOffloadingDestination().getType() == TYPES.EDGE_DATACENTER)
					record.edge++;
			}
		}
		EdgeServer home = world.homeOf(task.getEdgeDevice());
		if (failed) {
			tasksFailed++;
			windowFailed++;
			failures.merge(reason, 1, Integer::sum);
			logFailure(task, home, reason);
		} else {
			tasksOk++;
			windowOk++;
		}
		if (world.activePartitions().isEmpty()) {
			if (failed) {
				healthyFailed++;
				healthyFailures.merge(reason, 1, Integer::sum);
			} else
				healthyOk++;
			return;
		}
		for (TestbedWorld.ActivePartition partition : world.activePartitions()) {
			Integer group = partition.groupOf.get(home);
			if (group == null)
				continue;
			GroupRecord record = partition.groups.get(group);
			if (failed) {
				record.tasksFailed++;
				record.failureReasons.merge(reason, 1, Integer::sum);
			} else
				record.tasksOk++;
		}
	}

	/** Counts a device that attached to another access server. */
	void handover(ComputingNode device, EdgeServer from, EdgeServer to) {
		handovers++;
	}

	/**
	 * @param values   numbers, in any order
	 * @param fraction between 0 and 1
	 * @return that percentile of the values, or NaN when there are none
	 */
	static double percentile(List<Double> values, double fraction) {
		if (values.isEmpty())
			return Double.NaN;
		List<Double> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		return sorted.get((int) Math.min(sorted.size() - 1, Math.floor(fraction * sorted.size())));
	}

	/**
	 * @return the figures of this run that the sweep averages over its
	 *         replications, by name
	 */
	Map<String, Double> summary() {
		Map<String, Double> values = new LinkedHashMap<>();
		int partitionedOk = tasksOk - healthyOk;
		int partitionedFailed = tasksFailed - healthyFailed;
		values.put("tasks", (double) (tasksOk + tasksFailed));
		values.put("success_pct", rate(tasksOk, tasksFailed));
		values.put("success_whole_pct", rate(healthyOk, healthyFailed));
		values.put("success_partitioned_pct", rate(partitionedOk, partitionedFailed));
		double total = 0;
		for (double delay : delays)
			total += delay;
		values.put("delay_mean_ms", delays.isEmpty() ? Double.NaN : 1000 * total / delays.size());
		values.put("delay_p50_ms", 1000 * percentile(delays, 0.5));
		values.put("delay_p95_ms", 1000 * percentile(delays, 0.95));
		values.put("delay_p99_ms", 1000 * percentile(delays, 0.99));
		values.put("deployments", (double) deployments);
		values.put("deployment_failures", (double) deploymentFailures);
		values.put("handovers", (double) handovers);
		return values;
	}

	private static double rate(int ok, int failed) {
		return ok + failed == 0 ? Double.NaN : 100.0 * ok / (ok + failed);
	}

	/** Marks a task whose result is lost on its way back to the device. */
	void losingResult(Task task) {
		losingResult.add(task);
	}

	/**
	 * Records one failed task: when, whose, where it was in the network, why, and
	 * whether it was lost on its way to its host or with its result on the way back.
	 */
	private void logFailure(Task task, EdgeServer home, String reason) {
		String lostOn = "";
		if (losingResult.remove(task)) {
			lostOn = "result";
			lostOnResult++;
		} else if (task.getFailureReason() == Task.FailureReason.FAILED_DUE_TO_NETWORK_OUTAGE) {
			lostOn = "request";
			lostOnRequest++;
		}
		ComputingNode destination = task.getOffloadingDestination();
		String host = destination == null || destination == ComputingNode.NULL ? ""
				: destination.getType() == TYPES.CLOUD ? "cloud" : destination.getName();
		failureLog.add(csv(String.format(Locale.ROOT, "%.3f", world.now()),
				String.valueOf(task.getEdgeDevice().getId()), String.valueOf(world.deviceTypeOf(task.getEdgeDevice())),
				home == null ? "" : home.name(), groupsOf(home), reason, lostOn, host));
	}

	/** @return the partition groups of a server, as {@code partition:group}, or {@code none} */
	private String groupsOf(EdgeServer server) {
		StringBuilder groups = new StringBuilder();
		for (TestbedWorld.ActivePartition partition : world.activePartitions()) {
			Integer group = partition.groupOf.get(server);
			if (group != null)
				groups.append(groups.length() == 0 ? "" : " ").append(partition.spec.index).append(':').append(group);
		}
		return groups.length() == 0 ? "none" : groups.toString();
	}

	/**
	 * @param reasons failure counts by reason
	 * @return them as {@code REASON:count} separated by spaces, or {@code none}
	 */
	static String reasons(Map<String, Integer> reasons) {
		if (reasons.isEmpty())
			return "none";
		StringBuilder text = new StringBuilder();
		for (Map.Entry<String, Integer> reason : reasons.entrySet())
			text.append(text.length() == 0 ? "" : " ").append(reason.getKey()).append(':').append(reason.getValue());
		return text.toString();
	}

	// ---------------------------------------------------------- deployments

	/** Records the outcome of one deployment. */
	void deployment(Deployment deployment) {
		deployments++;
		if (deployment.success()) {
			deploymentLatencies.add(deployment.delay);
			if (deployment.onCloud)
				cloudDeployments++;
		} else
			deploymentFailures++;
		for (TestbedWorld.ActivePartition partition : world.activePartitions()) {
			Integer group = partition.groupOf.get(deployment.home);
			if (group == null)
				continue;
			partition.groups.get(group).deployments++;
			if (!deployment.success())
				partition.groups.get(group).deploymentFailures++;
		}
		List<String> row = new ArrayList<>(List.of(String.format(Locale.ROOT, "%.3f", world.now()),
				String.valueOf(deployment.device.getId()), String.valueOf(world.deviceTypeOf(deployment.device)),
				deployment.home.name(), deployment.service.name.replace(',', ' '), deployment.outcome,
				deployment.onCloud ? "cloud" : deployment.host == null ? "" : deployment.host.name(),
				String.format(Locale.ROOT, "%.2f", deployment.delay * 1000)));
		row.addAll(deployment.details());
		deploymentLog.add(String.join(",", row));
	}

	/**
	 * @param fraction a fraction between 0 and 1
	 * @return that percentile of the latencies of the successful deployments, in
	 *         seconds, or {@code NaN} when none succeeded
	 */
	double deploymentLatencyPercentile(double fraction) {
		return percentile(deploymentLatencies, fraction);
	}

	// ----------------------------------------------------------- partitions

	/** Records a partition once it has healed, group by group. */
	void partitionEnded(TestbedWorld.ActivePartition partition, double end) {
		for (GroupRecord group : partition.groups)
			partitionLog.add(String.format(Locale.ROOT, "%d,%.1f,%.1f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s,%s",
					partition.spec.index, partition.spec.start, end, group.index, group.nodes.size(),
					group.accessNodes, group.devices, group.gateways, group.accessNodesWithCloud, group.tasksOk,
					group.tasksFailed, group.deployments, group.deploymentFailures, reasons(group.failureReasons),
					group.deviceMix, String.join(" ", group.nodes)));
	}

	/** @return the partition rows, without header */
	List<String> partitionRows() {
		return Collections.unmodifiableList(partitionLog);
	}

	// ------------------------------------------------------------- timeline

	/** Takes one row of the timeline, with the strategy's own columns. */
	void sample() {
		double now = world.now();
		if (now <= lastSample)
			return;
		List<String> row = new ArrayList<>(List.of(String.format(Locale.ROOT, "%.1f", now),
				String.valueOf(world.activePartitions().size()), String.valueOf(world.topology().linksDown()),
				String.valueOf(windowOk), String.valueOf(windowFailed), String.valueOf(deployments),
				String.valueOf(deploymentFailures), String.valueOf(handovers), String.valueOf(detached())));
		row.addAll(world.strategy().timelineValues());
		timeline.add(String.join(",", row));
		windowOk = 0;
		windowFailed = 0;
		lastSample = now;
	}

	/** @return the devices that cannot reach an access server now */
	private int detached() {
		int detached = 0;
		for (ComputingNode device : world.homes().keySet())
			if (!world.attached(device))
				detached++;
		return detached;
	}

	// ---------------------------------------------------------------- files

	/**
	 * Writes the five files of the run, and lets the strategy write its own.
	 *
	 * @param folder the output folder
	 * @param label  the beginning of the names of the files of this run
	 * @throws IOException when a file cannot be written
	 */
	void write(Path folder, String label) throws IOException {
		Files.createDirectories(folder);
		PlacementStrategy strategy = world.strategy();
		write(folder.resolve(label + "_timeline.csv"), header(List.of("time_s", "active_partitions", "links_down",
				"tasks_ok", "tasks_failed", "deployments", "deployment_failures", "handovers", "detached_devices"),
				strategy.timelineColumns()),
				timeline);
		write(folder.resolve(label + "_failures.csv"),
				"time_s,device,device_type,home,partition_group,reason,lost_on,destination", failureLog);
		List<String> types = new ArrayList<>();
		for (TestbedWorld.DeviceType type : world.deviceTypes()) {
			TypeRecord record = byType.getOrDefault(type.name, new TypeRecord());
			long devices = world.homes().keySet().stream().filter(device -> world.deviceTypeOf(device) == type)
					.count();
			types.add(String.format(Locale.ROOT, "%s,%d,%s,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%s", type.name, devices,
					world.services().get(type.application).name, record.ok, record.failed, record.edge, record.cloud,
					record.ok == 0 ? 0 : record.delay / record.ok, percentile(record.delays, 0.5),
					percentile(record.delays, 0.95), percentile(record.delays, 0.99), reasons(record.reasons)));
		}
		write(folder.resolve(label + "_device_types.csv"),
				"device_type,devices,application,tasks_ok,tasks_failed,served_by_edge,served_by_cloud,mean_delay_s,"
						+ "p50_delay_s,p95_delay_s,p99_delay_s,failure_reasons",
				types);
		write(folder.resolve(label + "_partitions.csv"),
				"partition,start_s,end_s,group,nodes,access_nodes,devices,gateways,access_nodes_with_cloud,tasks_ok,"
						+ "tasks_failed,deployments,deployment_failures,failure_reasons,device_mix,members",
				partitionLog);
		write(folder.resolve(label + "_deployments.csv"), header(List.of("time_s", "device", "device_type", "home",
				"service", "outcome", "host", "latency_ms"), strategy.deploymentColumns()), deploymentLog);
		strategy.write(folder, label);
	}

	private static String header(List<String> columns, List<String> strategyColumns) {
		List<String> all = new ArrayList<>(columns);
		all.addAll(strategyColumns);
		return String.join(",", all);
	}

	private static String csv(String... cells) {
		return String.join(",", cells);
	}

	/**
	 * Writes a CSV file.
	 *
	 * @param file   the file
	 * @param header its first line
	 * @param rows   the rows that follow
	 * @throws IOException when it cannot be written
	 */
	public static void write(Path file, String header, List<String> rows) throws IOException {
		List<String> lines = new ArrayList<>(rows.size() + 1);
		lines.add(header);
		lines.addAll(rows);
		Files.write(file, lines, StandardCharsets.UTF_8);
	}
}
