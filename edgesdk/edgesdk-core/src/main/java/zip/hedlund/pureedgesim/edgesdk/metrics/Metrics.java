package zip.hedlund.pureedgesim.edgesdk.metrics;

import static zip.hedlund.pureedgesim.edgesdk.metrics.Direction.HIGHER_IS_BETTER;
import static zip.hedlund.pureedgesim.edgesdk.metrics.Direction.LOWER_IS_BETTER;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import zip.hedlund.pureedgesim.edgesdk.fault.FaultRecord;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultType;

/**
 * The built-in metric catalogue. Every metric is computed from {@link RunData}
 * after the run, from ground truth the algorithm never saw.
 */
public final class Metrics {

	private Metrics() {
	}

	// ---- tasks ---------------------------------------------------------------

	/** Share of generated tasks whose result came back before the deadline. */
	public static final Metric TASK_SUCCESS = Metric.of("task_success_pct", "%", HIGHER_IS_BETTER,
			r -> successPct(r, t -> true));

	/** Task success for tasks created while a partition was active (NaN without partition). */
	public static final Metric SUCCESS_DURING_PARTITION = Metric.of("task_success_partition_pct", "%",
			HIGHER_IS_BETTER, r -> successPct(r, TaskRecord::duringPartition));

	/** Task success for tasks created while no injected fault was active. */
	public static final Metric SUCCESS_OUTSIDE_FAULTS = Metric.of("task_success_no_fault_pct", "%", HIGHER_IS_BETTER,
			r -> successPct(r, t -> !t.duringFault()));

	/** Share of tasks lost to the network or to crashes (rejections and plain timeouts excluded). */
	public static final Metric TASKS_LOST = Metric.of("task_lost_pct", "%", LOWER_IS_BETTER, r -> Stats.pct(
			r.tasks().stream().filter(t -> switch (t.outcome()) {
			case NO_ROUTE, LINK_LOST, PACKET_LOSS, NODE_CRASHED, DEVICE_DOWN -> true;
			default -> false;
			}).count(), r.tasks().size()));

	/** Mean latency of the tasks whose result came back (late ones included), ms. */
	public static final Metric LATENCY_MEAN = Metric.of("latency_mean_ms", "ms", LOWER_IS_BETTER,
			r -> 1000 * Stats.mean(latencies(r)));

	public static final Metric LATENCY_P50 = Metric.of("latency_p50_ms", "ms", LOWER_IS_BETTER,
			r -> 1000 * Stats.percentile(latencies(r), 50));

	public static final Metric LATENCY_P95 = Metric.of("latency_p95_ms", "ms", LOWER_IS_BETTER,
			r -> 1000 * Stats.percentile(latencies(r), 95));

	public static final Metric LATENCY_P99 = Metric.of("latency_p99_ms", "ms", LOWER_IS_BETTER,
			r -> 1000 * Stats.percentile(latencies(r), 99));

	/** Attempts per task, a measure of redundancy and retries. */
	public static final Metric ATTEMPTS_PER_TASK = Metric.of("attempts_per_task", "", LOWER_IS_BETTER,
			r -> r.tasks().stream().mapToInt(TaskRecord::attempts).average().orElse(Double.NaN));

	// ---- energy and overhead --------------------------------------------------

	public static final Metric ENERGY_TOTAL = Metric.of("energy_total_wh", "Wh", LOWER_IS_BETTER,
			r -> r.energy().stream().mapToDouble(NodeEnergy::totalWh).sum());

	public static final Metric ENERGY_DEVICES = Metric.of("energy_devices_wh", "Wh", LOWER_IS_BETTER,
			r -> r.energy().stream().filter(e -> e.tier().equals("END_DEVICE")).mapToDouble(NodeEnergy::totalWh)
					.sum());

	public static final Metric ENERGY_NETWORK = Metric.of("energy_network_wh", "Wh", LOWER_IS_BETTER,
			r -> r.energy().stream().mapToDouble(NodeEnergy::networkWh).sum());

	/** Algorithm messages (orchestration and storage), kbit/s per node. */
	public static final Metric CONTROL_OVERHEAD = Metric.of("control_kbps_per_node", "kbit/s", LOWER_IS_BETTER,
			r -> (r.controlBytes() + r.storageBytes()) * 8 / 1000.0 / r.duration() / Math.max(1, r.nodeCount()));

	// ---- storage --------------------------------------------------------------

	public static final Metric STORAGE_AVAILABILITY = Metric.of("storage_availability_pct", "%", HIGHER_IS_BETTER,
			r -> Stats.pct(r.storage().stream().filter(StorageRecord::ok).count(), r.storage().size()));

	public static final Metric STORAGE_AVAILABILITY_PARTITION = Metric.of("storage_availability_partition_pct", "%",
			HIGHER_IS_BETTER, r -> {
				List<StorageRecord> during = r.storage().stream().filter(StorageRecord::duringPartition).toList();
				return Stats.pct(during.stream().filter(StorageRecord::ok).count(), during.size());
			});

	/** Share of successful reads that returned an outdated version. */
	public static final Metric STALE_READS = Metric.of("stale_read_pct", "%", LOWER_IS_BETTER, r -> {
		List<StorageRecord> reads = r.storage().stream().filter(s -> !s.write() && s.ok()).toList();
		return Stats.pct(reads.stream().filter(s -> s.versionsBehind() > 0).count(), reads.size());
	});

	/** Mean staleness of stale reads, seconds. */
	public static final Metric STALENESS = Metric.of("staleness_mean_s", "s", LOWER_IS_BETTER,
			r -> Stats.mean(r.storage().stream().filter(s -> !s.write() && s.ok() && s.versionsBehind() > 0)
					.mapToDouble(StorageRecord::stalenessS).toArray()));

	/** Mean number of keys whose live replicas disagree. */
	public static final Metric DIVERGENCE = Metric.of("divergent_keys_mean", "keys", LOWER_IS_BETTER,
			r -> r.timeline().stream().mapToInt(TimelineSample::divergentKeys).average().orElse(Double.NaN));

	// ---- recovery -------------------------------------------------------------

	/**
	 * Mean time from the end of a fault (partition heal, link repair, node restart)
	 * to the first successful task created after it by a device that lost a task
	 * during the fault.
	 */
	public static final Metric TIME_TO_RECOVER = Metric.of("time_to_recover_s", "s", LOWER_IS_BETTER, r -> {
		List<Double> values = new ArrayList<>();
		for (FaultRecord f : healedFaults(r)) {
			Set<String> affected = new HashSet<>();
			for (TaskRecord t : r.tasks())
				if (!t.outcome().succeeded() && t.createdAt() >= f.start() && t.createdAt() < f.end())
					affected.add(t.device());
			if (affected.isEmpty())
				continue;
			r.tasks().stream()
					.filter(t -> t.outcome().succeeded() && affected.contains(t.device()) && t.createdAt() >= f.end())
					.mapToDouble(TaskRecord::completedAt).min().ifPresent(c -> values.add(c - f.end()));
		}
		return Stats.mean(Stats.finite(values));
	});

	/**
	 * Mean time from a partition heal until the windowed task success rate is back
	 * within 5 points of its fault-free level.
	 */
	public static final Metric SUCCESS_RATE_RECOVERY = Metric.of("success_rate_recovery_s", "s", LOWER_IS_BETTER,
			r -> {
				double baseline = Stats.mean(r.timeline().stream()
						.filter(s -> !r.faults().faultActive(s.time()) && !Double.isNaN(s.successPct()))
						.mapToDouble(TimelineSample::successPct).toArray());
				if (Double.isNaN(baseline))
					return Double.NaN;
				List<Double> values = new ArrayList<>();
				for (FaultRecord f : r.faults().of(FaultType.PARTITION)) {
					if (f.end() >= r.duration())
						continue;
					r.timeline().stream().filter(s -> s.time() > f.end() && !Double.isNaN(s.successPct())
							&& s.successPct() >= baseline - 5).findFirst()
							.ifPresent(s -> values.add(s.time() - f.end()));
				}
				return Stats.mean(Stats.finite(values));
			});

	/** Mean time from a partition heal until no key diverges any more. */
	public static final Metric STORAGE_CONVERGENCE = Metric.of("storage_convergence_s", "s", LOWER_IS_BETTER, r -> {
		if (r.storage().isEmpty())
			return Double.NaN;
		List<Double> values = new ArrayList<>();
		for (FaultRecord f : r.faults().of(FaultType.PARTITION)) {
			if (f.end() >= r.duration())
				continue;
			r.timeline().stream().filter(s -> s.time() >= f.end() && s.divergentKeys() == 0).findFirst()
					.ifPresent(s -> values.add(s.time() - f.end()));
		}
		return Stats.mean(Stats.finite(values));
	});

	// ---- detection (only for algorithms that declare beliefs) ----------------

	/** Mean time from a crash or partition to its first correct suspicion. */
	public static final Metric DETECTION_LATENCY = Metric.of("detection_latency_s", "s", LOWER_IS_BETTER, r -> {
		if (r.declarations().isEmpty())
			return Double.NaN;
		List<Double> values = new ArrayList<>();
		for (FaultRecord f : detectableFaults(r))
			firstDetection(r, f).ifPresent(d -> values.add(d.time() - f.start()));
		return Stats.mean(Stats.finite(values));
	});

	/** Share of suspicions that were wrong (the target was up and reachable). */
	public static final Metric FALSE_SUSPICIONS = Metric.of("false_suspicion_pct", "%", LOWER_IS_BETTER, r -> {
		List<DeclarationRecord> s = r.declarations().stream().filter(DeclarationRecord::suspected).toList();
		return Stats.pct(s.stream().filter(d -> !d.correct()).count(), s.size());
	});

	/** Share of crashes and partitions never correctly suspected while they lasted. */
	public static final Metric MISSED_FAULTS = Metric.of("missed_faults_pct", "%", LOWER_IS_BETTER, r -> {
		if (r.declarations().isEmpty())
			return Double.NaN;
		List<FaultRecord> faults = detectableFaults(r);
		return Stats.pct(faults.stream().filter(f -> firstDetection(r, f).isEmpty()).count(), faults.size());
	});

	/** @return the default selection used when an experiment names no metrics */
	public static List<Metric> defaults() {
		return List.of(TASK_SUCCESS, SUCCESS_DURING_PARTITION, TASKS_LOST, LATENCY_MEAN, LATENCY_P95, TIME_TO_RECOVER,
				ENERGY_TOTAL, CONTROL_OVERHEAD, STORAGE_AVAILABILITY, STALE_READS, STORAGE_CONVERGENCE,
				DETECTION_LATENCY, FALSE_SUSPICIONS);
	}

	/** @return every built-in metric */
	public static List<Metric> all() {
		return List.of(TASK_SUCCESS, SUCCESS_DURING_PARTITION, SUCCESS_OUTSIDE_FAULTS, TASKS_LOST, LATENCY_MEAN,
				LATENCY_P50, LATENCY_P95, LATENCY_P99, ATTEMPTS_PER_TASK, ENERGY_TOTAL, ENERGY_DEVICES, ENERGY_NETWORK,
				CONTROL_OVERHEAD, STORAGE_AVAILABILITY, STORAGE_AVAILABILITY_PARTITION, STALE_READS, STALENESS,
				DIVERGENCE, TIME_TO_RECOVER, SUCCESS_RATE_RECOVERY, STORAGE_CONVERGENCE, DETECTION_LATENCY,
				FALSE_SUSPICIONS, MISSED_FAULTS);
	}

	/**
	 * A metric over a custom counter or sample published with
	 * {@link MetricsCollector}; samples are reported as {@code <name>.mean}.
	 */
	public static Metric custom(String name, Direction direction) {
		return Metric.of("custom." + name, "", direction, r -> r.custom().getOrDefault(name, Double.NaN));
	}

	public static Metric custom(String name) {
		return custom(name, HIGHER_IS_BETTER);
	}

	// ---- helpers --------------------------------------------------------------

	private static double successPct(RunData r, Predicate<TaskRecord> filter) {
		List<TaskRecord> ts = r.tasks().stream().filter(filter).toList();
		return Stats.pct(ts.stream().filter(t -> t.outcome().succeeded()).count(), ts.size());
	}

	private static double[] latencies(RunData r) {
		return r.tasks().stream().filter(t -> !Double.isNaN(t.completedAt())).mapToDouble(TaskRecord::latency)
				.toArray();
	}

	private static List<FaultRecord> healedFaults(RunData r) {
		return r.faults().records().stream()
				.filter(f -> !"mobility".equals(f.cause()) && f.end() < r.duration()
						&& (f.type() == FaultType.PARTITION || f.type() == FaultType.LINK_DOWN
								|| f.type() == FaultType.CRASH_RECOVERY))
				.toList();
	}

	private static List<FaultRecord> detectableFaults(RunData r) {
		return r.faults().records().stream().filter(f -> f.type() == FaultType.PARTITION
				|| f.type() == FaultType.CRASH_STOP || f.type() == FaultType.CRASH_RECOVERY).toList();
	}

	private static java.util.Optional<DeclarationRecord> firstDetection(RunData r, FaultRecord f) {
		List<Set<String>> groups = new ArrayList<>();
		if (f.type() == FaultType.PARTITION)
			for (String g : f.groups().split("\\|"))
				groups.add(new HashSet<>(Arrays.asList(g.split(","))));
		return r.declarations().stream().filter(d -> d.suspected() && d.correct() && f.activeAt(d.time()))
				.filter(d -> {
					if (f.type() != FaultType.PARTITION)
						return f.nodes().contains(d.target());
					for (Set<String> g : groups)
						if (g.contains(d.by()))
							return !g.contains(d.target());
					return false;
				}).findFirst();
	}
}
