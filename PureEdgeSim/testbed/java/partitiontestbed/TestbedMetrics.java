package partitiontestbed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters.TYPES;

/**
 * What the testbed measures, the same way for every strategy, and the files it
 * writes.
 * <p>
 * Every task is a {@link Job}, judged once at the end of the run against the
 * requirement of its service: a {@code deadline} service needs its tasks done
 * before their deadline and accepts losing some; a {@code lossless} one needs
 * them done at all, within its delivery window, late or not. The figures are
 * generic, so any two strategies compare on them:
 * <ul>
 * <li><b>requirement met</b>: the share of the judged tasks that met the
 * requirement of their service, overall, while a partition was in progress and
 * while none was, per requirement and per service, and the number of services
 * that reach their target ({@code services.<n>.target_pct});</li>
 * <li><b>outcomes</b>: done on time, late, or lost;</li>
 * <li><b>delay</b>: percentiles of the time from creation to done, over the tasks
 * that were done;</li>
 * <li><b>cost</b>: attempts per task, runs beyond the first that count, instances
 * started, memory held, control traffic and data lost on the network;</li>
 * <li><b>exposure</b>: for each kind of trouble a task can meet while it is not
 * done (its device detached, handed over or changed group, a host of it failed,
 * a partition started), how many tasks met it and how many of those still met
 * their requirement: how well the strategy recovers.</li>
 * </ul>
 * Files of a run, named after the algorithm, the number of devices and the
 * delivery mode:
 * <ul>
 * <li>{@code *_tasks.csv}: one row per task, with its outcome, attempts and
 * exposures;</li>
 * <li>{@code *_timeline.csv}: one row every {@code metrics.sample_interval_s},
 * with the state of the network and the tasks created in the interval, and the
 * strategy's own columns;</li>
 * <li>{@code *_device_types.csv}: one row per type of end device;</li>
 * <li>{@code *_partitions.csv}: one row per group of every partition: what it held
 * when the partition started, the nodes that joined or left it, and the tasks
 * created in it while it lasted.</li>
 * </ul>
 *
 * @see TestbedSimulationManager#onSimulationEnd()
 */
public class TestbedMetrics {

	/** One group of one partition. */
	public static final class GroupRecord {
		/** Index of the group in its partition. */
		public final int index;
		/** Names of its servers when the partition started. */
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
		/** Moving servers that joined or left it while the partition lasted. */
		int serversJoined;
		int serversLeft;
		/** Devices that attached to an access server of it from another group, or left it. */
		int devicesJoined;
		int devicesLeft;

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

	/** The judged tasks of a set, with how they ended. */
	static final class Tally {
		int judged;
		int met;
		int onTime;
		int late;
		int lost;
		int censored;
		final List<Double> delays = new ArrayList<>();
		int edge;
		int cloud;
		int device;
		int attempts;
		int duplicates;

		void add(Job job, double end) {
			attempts += job.attempts.size();
			duplicates += job.duplicates;
			Job.Outcome outcome = job.outcome(end);
			if (outcome == Job.Outcome.CENSORED) {
				censored++;
				return;
			}
			judged++;
			if (Boolean.TRUE.equals(job.met(end)))
				met++;
			if (outcome == Job.Outcome.ON_TIME)
				onTime++;
			else if (outcome == Job.Outcome.LATE)
				late++;
			else
				lost++;
			if (job.isDone()) {
				delays.add(job.doneAt() - job.createdAt());
				if (job.doneBy != null && job.doneBy.host().getType() == TYPES.CLOUD)
					cloud++;
				else if (job.doneBy != null && job.doneBy.host().getType() == TYPES.EDGE_DEVICE)
					device++;
				else
					edge++;
			}
		}

		double pct(int count) {
			return judged == 0 ? Double.NaN : 100.0 * count / judged;
		}
	}

	/** A partition that ended, with when. */
	private static final class Ended {
		final TestbedWorld.ActivePartition partition;
		final double end;

		Ended(TestbedWorld.ActivePartition partition, double end) {
			this.partition = partition;
			this.end = end;
		}
	}

	private final TestbedWorld world;

	/** Times a device attached to another access server. */
	int handovers;
	/** Servers and devices that changed partition group. */
	int groupTransfers;
	/** Nodes refused by a group, each counted once per partition and group. */
	private final Set<String> refusedJoins = new HashSet<>();

	private final List<Double> sampleTimes = new ArrayList<>();
	private final List<String> sampleRows = new ArrayList<>();
	private final List<Ended> ended = new ArrayList<>();
	private double lastSample = -1;
	private long lastControlBytes;

	TestbedMetrics(TestbedWorld world) {
		this.world = world;
	}

	// ---------------------------------------------------------------- events

	/** Counts a device that attached to another access server. */
	void handover(ComputingNode device, EdgeServer from, EdgeServer to) {
		handovers++;
	}

	/** Counts a moving server that joined another group of a partition. */
	void serverChangedGroup(TestbedWorld.ActivePartition partition, int from, int to) {
		groupTransfers++;
		partition.groups.get(from).serversLeft++;
		partition.groups.get(to).serversJoined++;
	}

	/** Counts a device that attached to an access server of another group of a partition. */
	void deviceChangedGroup(TestbedWorld.ActivePartition partition, int from, int to) {
		groupTransfers++;
		partition.groups.get(from).devicesLeft++;
		partition.groups.get(to).devicesJoined++;
	}

	/**
	 * Counts a node the strategy did not admit into a group, once per partition
	 * and group however often it asks.
	 */
	void joinRefused(TestbedWorld.ActivePartition partition, ComputingNode node, int group) {
		refusedJoins.add(partition.spec.index + ":" + group + ":" + node.getId());
	}

	/** Records a partition once it has healed. */
	void partitionEnded(TestbedWorld.ActivePartition partition, double end) {
		ended.add(new Ended(partition, end));
	}

	// -------------------------------------------------------------- figures

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

	private double end() {
		return world.now();
	}

	/** @return how every task of the run ended */
	Tally all() {
		Tally tally = new Tally();
		for (Job job : world.engine.jobs())
			tally.add(job, end());
		return tally;
	}

	/**
	 * @return the figures of this run that the sweep averages over its
	 *         replications, by name: the same names for every strategy
	 */
	Map<String, Double> summary() {
		double end = end();
		TaskEngine engine = world.engine;
		Tally all = new Tally();
		Tally partitioned = new Tally();
		Tally healthy = new Tally();
		Tally deadline = new Tally();
		Tally lossless = new Tally();
		Map<Service, Tally> byService = new LinkedHashMap<>();
		Map<Job.Exposure, Tally> byExposure = new EnumMap<>(Job.Exposure.class);
		// A run is redundant when what it ran, the task or one of its parts, had run
		// already: the parts of a divided task are not copies of each other.
		int ranOnce = 0;
		for (Job job : engine.jobs()) {
			all.add(job, end);
			(job.partitioned ? partitioned : healthy).add(job, end);
			(job.service().lossless ? lossless : deadline).add(job, end);
			byService.computeIfAbsent(job.service(), key -> new Tally()).add(job, end);
			for (Job.Exposure exposure : job.exposures)
				byExposure.computeIfAbsent(exposure, key -> new Tally()).add(job, end);
			Set<Integer> ran = new HashSet<>();
			for (Attempt attempt : job.attempts)
				if (!Double.isNaN(attempt.ranAt()))
					ran.add(attempt.part());
			ranOnce += ran.size();
		}
		Map<String, Double> values = new LinkedHashMap<>();
		values.put("tasks", (double) all.judged);
		values.put("tasks_censored", (double) all.censored);
		values.put("requirement_met_pct", all.pct(all.met));
		values.put("requirement_met_partitioned_pct", partitioned.pct(partitioned.met));
		values.put("requirement_met_healthy_pct", healthy.pct(healthy.met));
		values.put("deadline_met_pct", deadline.pct(deadline.met));
		values.put("lossless_met_pct", lossless.pct(lossless.met));
		values.put("on_time_pct", all.pct(all.onTime));
		values.put("late_pct", all.pct(all.late));
		values.put("lost_pct", all.pct(all.lost));
		values.put("lost_partitioned_pct", partitioned.pct(partitioned.lost));
		values.put("delay_p50_ms", 1000 * percentile(all.delays, 0.5));
		values.put("delay_p95_ms", 1000 * percentile(all.delays, 0.95));
		values.put("delay_p99_ms", 1000 * percentile(all.delays, 0.99));
		int withTarget = 0;
		int onTarget = 0;
		for (Map.Entry<Service, Tally> service : byService.entrySet()) {
			Tally tally = service.getValue();
			double met = tally.pct(tally.met);
			values.put("met_pct[" + service.getKey().name.replace(',', ' ') + "]", met);
			if (!Double.isNaN(service.getKey().targetPct)) {
				withTarget++;
				if (met >= service.getKey().targetPct)
					onTarget++;
			}
		}
		if (withTarget > 0) {
			values.put("services_with_target", (double) withTarget);
			values.put("services_on_target", (double) onTarget);
		}
		for (Job.Exposure exposure : Job.Exposure.values()) {
			Tally tally = byExposure.getOrDefault(exposure, new Tally());
			String name = exposure.name().toLowerCase(Locale.ROOT);
			values.put("exposed_" + name + "_tasks", (double) tally.judged);
			values.put("exposed_" + name + "_met_pct", tally.pct(tally.met));
		}
		int jobs = engine.jobs().size();
		values.put("attempts_per_task", jobs == 0 ? Double.NaN : (double) engine.attemptsDispatched / jobs);
		values.put("redundant_runs_pct",
				engine.attemptsRun == 0 ? 0 : 100.0 * (engine.attemptsRun - ranOnce) / engine.attemptsRun);
		values.put("instances_started", (double) engine.instancesStarted);
		engine.closeMemoryIntegral();
		int servers = world.servers().size();
		values.put("instance_memory_mb_per_server", end <= 0 ? Double.NaN : engine.busyMemoryIntegral / end / servers);
		values.put("control_kbps_per_server", end <= 0 ? Double.NaN : engine.controlBytes * 8 / 1000.0 / end / servers);
		values.put("control_messages_lost", (double) engine.controlLost);
		values.put("data_transfers_lost", (double) engine.dataLost);
		values.put("handovers", (double) handovers);
		values.put("group_transfers", (double) groupTransfers);
		values.put("joins_refused", (double) refusedJoins.size());
		return values;
	}

	// ------------------------------------------------------------- timeline

	/** Takes one row of the timeline, with the strategy's own columns. */
	void sample() {
		double now = world.now();
		if (now <= lastSample)
			return;
		TaskEngine engine = world.engine;
		double interval = lastSample < 0 ? now : now - lastSample;
		double kbps = interval <= 0 ? 0
				: (engine.controlBytes - lastControlBytes) * 8 / 1000.0 / interval / world.servers().size();
		lastControlBytes = engine.controlBytes;
		List<String> row = new ArrayList<>(List.of(String.valueOf(world.activePartitions().size()),
				String.valueOf(world.topology().linksDown()), String.valueOf(handovers), String.valueOf(detached()),
				String.valueOf(engine.instanceCount()), String.format(Locale.ROOT, "%.0f", engine.memoryTotal()),
				String.format(Locale.ROOT, "%.2f", kbps)));
		row.addAll(world.strategy().timelineValues());
		sampleTimes.add(now);
		sampleRows.add(String.join(",", row));
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
	 * Writes the files of the run, and lets the strategy write its own.
	 *
	 * @param folder the output folder
	 * @param label  the beginning of the names of the files of this run
	 * @throws IOException when a file cannot be written
	 */
	void write(Path folder, String label) throws IOException {
		Files.createDirectories(folder);
		double end = end();
		List<Job> jobs = world.engine.jobs();
		OrchestrationStrategy strategy = world.strategy();

		// Tasks
		List<String> taskRows = new ArrayList<>();
		for (Job job : jobs) {
			Boolean met = job.met(end);
			StringBuilder exposures = new StringBuilder();
			for (Job.Exposure exposure : job.exposures)
				exposures.append(exposures.length() == 0 ? "" : " ").append(exposure.name().toLowerCase(Locale.ROOT));
			taskRows.add(String.join(",", String.valueOf(job.id()), job.device().getName(),
					String.valueOf(world.deviceTypeOf(job.device())), job.service().name.replace(',', ' '),
					job.stage() == null ? "" : job.stage(), job.service().requirement(),
					String.format(Locale.ROOT, "%.3f", job.createdAt()),
					job.isDone() ? String.format(Locale.ROOT, "%.3f", job.doneAt()) : "",
					job.isDone() ? String.format(Locale.ROOT, "%.1f", 1000 * (job.doneAt() - job.createdAt())) : "",
					job.outcome(end).name().toLowerCase(Locale.ROOT), met == null ? "" : String.valueOf(met),
					String.valueOf(job.attempts.size()), String.valueOf(job.duplicates),
					job.doneBy == null ? "" : job.doneBy.host().getName(), String.valueOf(job.partitioned),
					exposures.toString()));
		}
		write(folder.resolve(label + "_tasks.csv"),
				"task,device,device_type,service,stage,requirement,created_s,done_s,delay_ms,outcome,met,attempts,"
						+ "duplicates,host,partitioned,exposures",
				taskRows);

		// Timeline: the state sampled during the run, and the tasks created in each interval
		List<String> timeline = new ArrayList<>();
		int next = 0;
		List<Job> sorted = new ArrayList<>(jobs);
		sorted.sort((a, b) -> Double.compare(a.createdAt(), b.createdAt()));
		for (int i = 0; i < sampleTimes.size(); i++) {
			Tally tally = new Tally();
			while (next < sorted.size() && sorted.get(next).createdAt() <= sampleTimes.get(i))
				tally.add(sorted.get(next++), end);
			timeline.add(String.format(Locale.ROOT, "%.1f,%d,%d,%s,%s,%s", sampleTimes.get(i), tally.judged,
					tally.censored, cell(tally.pct(tally.met)), cell(tally.pct(tally.lost)), sampleRows.get(i)));
		}
		write(folder.resolve(label + "_timeline.csv"),
				header(List.of("time_s", "tasks_created", "tasks_censored", "requirement_met_pct", "lost_pct",
						"active_partitions", "links_down", "handovers", "detached_devices", "instances",
						"instance_memory_mb", "control_kbps_per_server"), strategy.timelineColumns()),
				timeline);

		// Device types
		List<String> types = new ArrayList<>();
		for (TestbedWorld.DeviceType type : world.deviceTypes()) {
			Tally tally = new Tally();
			for (Job job : jobs)
				if (world.deviceTypeOf(job.device()) == type)
					tally.add(job, end);
			long devices = world.homes().keySet().stream().filter(device -> world.deviceTypeOf(device) == type)
					.count();
			Service service = world.services().get(type.application);
			types.add(String.format(Locale.ROOT, "%s,%d,%s,%s,%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%d,%d", type.name,
					devices, service.name.replace(',', ' '), service.requirement(), tally.judged, tally.censored,
					cell(tally.pct(tally.met)), cell(tally.pct(tally.onTime)), cell(tally.pct(tally.late)),
					cell(tally.pct(tally.lost)), cell(1000 * mean(tally.delays)),
					cell(1000 * percentile(tally.delays, 0.5)), cell(1000 * percentile(tally.delays, 0.95)),
					cell(1000 * percentile(tally.delays, 0.99)), tally.edge, tally.cloud, tally.device,
					tally.attempts, tally.duplicates));
		}
		write(folder.resolve(label + "_device_types.csv"),
				"device_type,devices,service,requirement,tasks,tasks_censored,requirement_met_pct,on_time_pct,late_pct,"
						+ "lost_pct,mean_delay_ms,p50_delay_ms,p95_delay_ms,p99_delay_ms,done_on_edge,done_on_cloud,"
						+ "done_on_device,attempts,duplicates",
				types);

		write(folder.resolve(label + "_partitions.csv"),
				"partition,start_s,end_s,group,nodes,access_nodes,devices,gateways,access_nodes_with_cloud,tasks,"
						+ "requirement_met_pct,lost_pct,servers_joined,servers_left,devices_joined,devices_left,"
						+ "device_mix,members",
				partitionRows());
		strategy.write(folder, label);
	}

	/** @return one row per group of every partition, with the tasks created in it while it lasted */
	List<String> partitionRows() {
		double end = end();
		List<Ended> all = new ArrayList<>(ended);
		for (TestbedWorld.ActivePartition partition : world.activePartitions())
			all.add(new Ended(partition, end));
		List<String> rows = new ArrayList<>();
		for (Ended entry : all) {
			TestbedWorld.ActivePartition partition = entry.partition;
			Map<Integer, Tally> byGroup = new LinkedHashMap<>();
			for (Job job : world.engine.jobs()) {
				Integer group = job.groups.get(partition);
				if (group != null)
					byGroup.computeIfAbsent(group, key -> new Tally()).add(job, end);
			}
			for (GroupRecord group : partition.groups) {
				Tally tally = byGroup.getOrDefault(group.index, new Tally());
				rows.add(String.format(Locale.ROOT, "%d,%.1f,%.1f,%d,%d,%d,%d,%d,%d,%d,%s,%s,%d,%d,%d,%d,%s,%s",
						partition.spec.index, partition.spec.start, entry.end, group.index, group.nodes.size(),
						group.accessNodes, group.devices, group.gateways, group.accessNodesWithCloud, tally.judged,
						cell(tally.pct(tally.met)), cell(tally.pct(tally.lost)), group.serversJoined, group.serversLeft,
						group.devicesJoined, group.devicesLeft, group.deviceMix, String.join(" ", group.nodes)));
			}
		}
		return rows;
	}

	private static double mean(List<Double> values) {
		return values.isEmpty() ? Double.NaN : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
	}

	private static String cell(double value) {
		return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.2f", value);
	}

	private static String header(List<String> columns, List<String> strategyColumns) {
		List<String> all = new ArrayList<>(columns);
		all.addAll(strategyColumns);
		return String.join(",", all);
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
