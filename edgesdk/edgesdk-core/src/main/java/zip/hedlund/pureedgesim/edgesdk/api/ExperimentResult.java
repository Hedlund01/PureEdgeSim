package zip.hedlund.pureedgesim.edgesdk.api;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import zip.hedlund.pureedgesim.edgesdk.metrics.ComparisonReport;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metric;
import zip.hedlund.pureedgesim.edgesdk.metrics.Stats;

/**
 * Every run of an experiment and the comparison between candidates.
 */
public final class ExperimentResult {

	private final Path output;
	private final List<RunResult> runs;
	private final List<Metric> metrics;
	private final ComparisonReport report;
	private final String baseline;

	ExperimentResult(Path output, List<RunResult> runs, List<Metric> metrics, ComparisonReport report,
			String baseline) {
		this.output = output;
		this.runs = List.copyOf(runs);
		this.metrics = List.copyOf(metrics);
		this.report = report;
		this.baseline = baseline;
	}

	public Path output() {
		return output;
	}

	public List<RunResult> runs() {
		return runs;
	}

	public List<RunResult> runs(String candidate) {
		return runs.stream().filter(r -> r.candidate().equals(candidate)).toList();
	}

	public ComparisonReport report() {
		return report;
	}

	public String baseline() {
		return baseline;
	}

	public List<Metric> metrics() {
		return metrics;
	}

	/** @return the candidate names, in order */
	public List<String> candidates() {
		Set<String> out = new LinkedHashSet<>();
		runs.forEach(r -> out.add(r.candidate()));
		return new ArrayList<>(out);
	}

	/** @return the mean of a metric over every run of a candidate (all conditions) */
	public double mean(String candidate, Metric metric) {
		return Stats.mean(runs(candidate).stream().mapToDouble(r -> r.value(metric)).filter(v -> !Double.isNaN(v))
				.toArray());
	}

	/** @return the mean over the runs of a candidate under one condition (fault model label) */
	public double mean(String candidate, String condition, Metric metric) {
		return Stats.mean(runs(candidate).stream().filter(r -> r.condition().equals(condition))
				.mapToDouble(r -> r.value(metric)).filter(v -> !Double.isNaN(v)).toArray());
	}

	/** @return {@code mean(candidate) - mean(reference)} over all conditions */
	public double delta(String candidate, String reference, Metric metric) {
		return mean(candidate, metric) - mean(reference, metric);
	}

	/** @return the candidate with the best mean, by the metric's direction */
	public Optional<String> best(Metric metric) {
		String best = null;
		double bestValue = Double.NaN;
		for (String c : candidates()) {
			double v = mean(c, metric);
			if (metric.direction().better(v, bestValue)) {
				best = c;
				bestValue = v;
			}
		}
		return Optional.ofNullable(best);
	}

	/** Prints the comparison as a table, one block per condition. */
	public void printTable(PrintStream out) {
		List<String> cands = candidates();
		for (String condition : report.conditions()) {
			out.println();
			out.println("== " + condition + "  (mean ± 95% CI, Δ vs " + baseline + ", * = best)");
			StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "%-32s", "metric"));
			for (String c : cands)
				head.append(String.format(Locale.ROOT, " %26s", c));
			out.println(head);
			for (Metric m : metrics) {
				StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-32s",
						m.name() + (m.unit().isEmpty() ? "" : " [" + m.unit() + "]")));
				for (String c : cands) {
					String cell = report.row(condition, c, m.name()).map(r -> {
						String s = Double.isNaN(r.mean()) ? "-" : String.format(Locale.ROOT, "%.2f", r.mean());
						if (!Double.isNaN(r.ci95()))
							s += String.format(Locale.ROOT, "±%.2f", r.ci95());
						if (!c.equals(baseline) && !Double.isNaN(r.delta()))
							s += String.format(Locale.ROOT, " (%+.2f)", r.delta());
						return s + (r.best() ? "*" : "");
					}).orElse("-");
					line.append(String.format(Locale.ROOT, " %26s", cell));
				}
				out.println(line);
			}
		}
		out.println();
		out.println("Results: " + output.toAbsolutePath());
	}
}
