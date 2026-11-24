package zip.hedlund.pureedgesim.edgesdk.metrics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultRecord;

/**
 * Writes the result files of an experiment:
 * {@code runs.csv, tasks.csv, faults.csv, timeline.csv, storage.csv,
 * comparison.csv, comparison_wide.csv}.
 */
public final class CsvExporter {

	private CsvExporter() {
	}

	public static void writeAll(Path dir, List<RunResult> runs, List<Metric> metrics, ComparisonReport report) {
		try {
			Files.createDirectories(dir);
			writeRuns(dir.resolve("runs.csv"), runs, metrics);
			writeTasks(dir.resolve("tasks.csv"), runs);
			writeFaults(dir.resolve("faults.csv"), runs);
			writeTimeline(dir.resolve("timeline.csv"), runs);
			writeStorage(dir.resolve("storage.csv"), runs);
			writeComparison(dir.resolve("comparison.csv"), report);
			writeWide(dir.resolve("comparison_wide.csv"), report);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<String> paramNames(List<RunResult> runs) {
		TreeSet<String> names = new TreeSet<>();
		runs.forEach(r -> names.addAll(r.data().params().keySet()));
		return new ArrayList<>(names);
	}

	private static String id(RunResult r, List<String> params) {
		RunData d = r.data();
		StringBuilder sb = new StringBuilder();
		sb.append(cell(d.runId())).append(',').append(cell(d.candidate())).append(',').append(cell(d.condition()));
		for (String p : params)
			sb.append(',').append(cell(d.params().getOrDefault(p, "")));
		sb.append(',').append(d.replication());
		return sb.toString();
	}

	private static String idHeader(List<String> params) {
		StringBuilder sb = new StringBuilder("run_id,candidate,condition");
		for (String p : params)
			sb.append(",param.").append(p);
		return sb.append(",replication").toString();
	}

	static void writeRuns(Path file, List<RunResult> runs, List<Metric> metrics) throws IOException {
		List<String> params = paramNames(runs);
		TreeSet<String> custom = new TreeSet<>();
		runs.forEach(r -> custom.addAll(r.data().custom().keySet()));
		TreeSet<String> lostReasons = new TreeSet<>();
		runs.forEach(r -> lostReasons.addAll(r.data().transfersLost().keySet()));
		try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			StringBuilder h = new StringBuilder(idHeader(params))
					.append(",scenario,workload_seed,fault_seed,algorithm_seed,tasks,storage_ops,faults,"
							+ "control_bytes,storage_bytes,task_bytes");
			for (Metric m : metrics)
				h.append(',').append(cell(m.name()));
			for (String l : lostReasons)
				h.append(",transfers_lost.").append(l);
			for (String c : custom)
				h.append(",custom.").append(cell(c));
			w.write(h.toString());
			w.newLine();
			for (RunResult r : runs) {
				RunData d = r.data();
				StringBuilder sb = new StringBuilder(id(r, params));
				sb.append(',').append(cell(d.scenario())).append(',').append(d.workloadSeed()).append(',')
						.append(d.faultSeed()).append(',').append(d.algorithmSeed()).append(',')
						.append(d.tasks().size()).append(',').append(d.storage().size()).append(',')
						.append(d.faults().records().size()).append(',').append(d.controlBytes()).append(',')
						.append(d.storageBytes()).append(',').append(d.taskBytes());
				for (Metric m : metrics)
					sb.append(',').append(num(r.value(m)));
				for (String l : lostReasons)
					sb.append(',').append(d.transfersLost().getOrDefault(l, 0L));
				for (String c : custom)
					sb.append(',').append(num(d.custom().getOrDefault(c, Double.NaN)));
				w.write(sb.toString());
				w.newLine();
			}
		}
	}

	static void writeTasks(Path file, List<RunResult> runs) throws IOException {
		List<String> params = paramNames(runs);
		try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			w.write(idHeader(params) + ",task_id,device,workload,created_at,deadline,outcome,executed_on,completed_at,"
					+ "latency_s,attempts,during_partition,during_fault,parts");
			w.newLine();
			for (RunResult r : runs) {
				String id = id(r, params);
				for (TaskRecord t : r.data().tasks()) {
					w.write(id + ',' + t.id() + ',' + cell(t.device()) + ',' + cell(t.workload()) + ','
							+ num(t.createdAt()) + ',' + num(t.deadline()) + ',' + t.outcome() + ','
							+ cell(t.executedOn()) + ',' + num(t.completedAt()) + ',' + num(t.latency()) + ','
							+ t.attempts() + ',' + t.duringPartition() + ',' + t.duringFault() + ','
							+ cell(String.join("+", t.parts())));
					w.newLine();
				}
			}
		}
	}

	static void writeFaults(Path file, List<RunResult> runs) throws IOException {
		List<String> params = paramNames(runs);
		try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			w.write(idHeader(params) + ",fault_id,type,target,cause,start,end,nodes,groups");
			w.newLine();
			for (RunResult r : runs) {
				String id = id(r, params);
				for (FaultRecord f : r.data().faults().records()) {
					w.write(id + ',' + f.id() + ',' + f.type() + ',' + cell(f.target()) + ',' + cell(f.cause()) + ','
							+ num(f.start()) + ',' + num(Math.min(f.end(), r.data().duration())) + ','
							+ cell(String.join(" ", f.nodes())) + ',' + cell(f.groups()));
					w.newLine();
				}
			}
		}
	}

	static void writeTimeline(Path file, List<RunResult> runs) throws IOException {
		List<String> params = paramNames(runs);
		try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			w.write(idHeader(params) + ",time,links_down,nodes_down,partition_active,tasks_done,tasks_failed,"
					+ "success_pct,divergent_keys,control_bytes");
			w.newLine();
			for (RunResult r : runs) {
				String id = id(r, params);
				for (TimelineSample s : r.data().timeline()) {
					w.write(id + ',' + num(s.time()) + ',' + s.linksDown() + ',' + s.nodesDown() + ','
							+ s.partitionActive() + ',' + s.tasksDone() + ',' + s.tasksFailed() + ','
							+ num(s.successPct()) + ',' + s.divergentKeys() + ',' + s.controlBytes());
					w.newLine();
				}
			}
		}
	}

	static void writeStorage(Path file, List<RunResult> runs) throws IOException {
		List<String> params = paramNames(runs);
		try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			w.write(idHeader(params) + ",op_id,type,key,client,issued_at,answered_at,ok,versions_behind,staleness_s,"
					+ "during_partition");
			w.newLine();
			for (RunResult r : runs) {
				String id = id(r, params);
				for (StorageRecord s : r.data().storage()) {
					w.write(id + ',' + s.id() + ',' + (s.write() ? "write" : "read") + ',' + cell(s.key()) + ','
							+ cell(s.client()) + ',' + num(s.issuedAt()) + ',' + num(s.answeredAt()) + ',' + s.ok()
							+ ',' + s.versionsBehind() + ',' + num(s.stalenessS()) + ',' + s.duringPartition());
					w.newLine();
				}
			}
		}
	}

	static void writeComparison(Path file, ComparisonReport report) throws IOException {
		try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			w.write("condition,candidate,metric,unit,direction,n,mean,std,ci95,delta_vs_baseline,delta_pct,best");
			w.newLine();
			for (ComparisonReport.Row r : report.rows()) {
				w.write(cell(r.condition()) + ',' + cell(r.candidate()) + ',' + cell(r.metric()) + ',' + cell(r.unit())
						+ ',' + r.direction() + ',' + r.n() + ',' + num(r.mean()) + ',' + num(r.std()) + ','
						+ num(r.ci95()) + ',' + num(r.delta()) + ',' + num(r.deltaPct()) + ',' + r.best());
				w.newLine();
			}
		}
	}

	static void writeWide(Path file, ComparisonReport report) throws IOException {
		List<String> metrics = report.rows().stream().map(ComparisonReport.Row::metric).distinct().toList();
		try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			StringBuilder h = new StringBuilder("condition,candidate,runs");
			for (String m : metrics)
				h.append(',').append(cell(m)).append(',').append(cell(m + "_ci95"));
			w.write(h.toString());
			w.newLine();
			for (String cond : report.conditions())
				for (String cand : report.candidates()) {
					StringBuilder sb = new StringBuilder(cell(cond)).append(',').append(cell(cand));
					int n = 0;
					StringBuilder vals = new StringBuilder();
					for (String m : metrics) {
						var row = report.row(cond, cand, m);
						n = Math.max(n, row.map(ComparisonReport.Row::n).orElse(0));
						vals.append(',').append(num(row.map(ComparisonReport.Row::mean).orElse(Double.NaN)))
								.append(',').append(num(row.map(ComparisonReport.Row::ci95).orElse(Double.NaN)));
					}
					w.write(sb.append(',').append(n).append(vals).toString());
					w.newLine();
				}
		}
	}

	static String num(double v) {
		if (Double.isNaN(v) || Double.isInfinite(v))
			return "";
		return String.format(Locale.ROOT, "%.6g", v);
	}

	static String cell(String s) {
		if (s == null)
			return "";
		if (s.contains(",") || s.contains("\"") || s.contains("\n"))
			return '"' + s.replace("\"", "\"\"") + '"';
		return s;
	}

	/** Reads a CSV header line, for tests. */
	public static List<String> header(Path file) throws IOException {
		try (var lines = Files.lines(file)) {
			return List.of(lines.findFirst().orElse("").split(","));
		}
	}
}
