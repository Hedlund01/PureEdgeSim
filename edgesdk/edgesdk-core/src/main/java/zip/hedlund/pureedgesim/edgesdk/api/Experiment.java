package zip.hedlund.pureedgesim.edgesdk.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import zip.hedlund.pureedgesim.edgesdk.engine.RunSpec;
import zip.hedlund.pureedgesim.edgesdk.engine.Runner;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultModel;
import zip.hedlund.pureedgesim.edgesdk.metrics.ComparisonCharts;
import zip.hedlund.pureedgesim.edgesdk.metrics.ComparisonReport;
import zip.hedlund.pureedgesim.edgesdk.metrics.CsvExporter;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metric;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.metrics.RunData;

/**
 * Runs one or many candidates, under one or many conditions (fault models and
 * swept parameters), several times, and compares them.
 * <p>
 * <b>Common random numbers.</b> For a given condition and replication every
 * candidate gets the same workload seed (tasks, storage operations, mobility)
 * and the same fault seed (hence the same faults); only its own algorithm seed
 * differs. Differences in the metrics therefore come from the algorithms.
 *
 * <pre>{@code
 * ExperimentResult r = Experiment.on(scenario)
 *     .candidate(Candidate.of("cloud-only", CloudOnly::new))
 *     .candidate(Candidate.of("fallback", HeartbeatFallback::new))
 *     .faultModels(NoFaults.INSTANCE, partition)
 *     .vary("devices", List.of(10, 20), (b, n) -> b.addEndDevices(DevicePresets.UAV, n))
 *     .replications(5).baseline("cloud-only")
 *     .metrics(Metrics.TASK_SUCCESS, Metrics.TIME_TO_RECOVER)
 *     .run(Path.of("output/exp"));
 * }</pre>
 */
public final class Experiment {

	private record Dimension(String name, List<Object> values, BiFunction<ScenarioBuilder, Object, ScenarioBuilder> apply) {
	}

	private final Scenario base;
	private final List<Candidate> candidates = new ArrayList<>();
	private final List<FaultModel> faultModels = new ArrayList<>();
	private final List<Dimension> dimensions = new ArrayList<>();
	private final List<Metric> metrics = new ArrayList<>();
	private int replications = 1;
	private String baseline;
	private boolean charts = true;
	private boolean quiet = true;
	private boolean progress = true;

	private Experiment(Scenario base) {
		this.base = base;
	}

	public static Experiment on(Scenario scenario) {
		return new Experiment(scenario);
	}

	public Experiment candidate(Candidate c) {
		if (candidates.stream().anyMatch(x -> x.name().equals(c.name())))
			throw new IllegalArgumentException("duplicate candidate \"" + c.name() + "\"");
		// run ids hold the file-safe name: two names that map to the same one would share output
		for (Candidate x : candidates)
			if (fileSafe(x.name()).equals(fileSafe(c.name())))
				throw new IllegalArgumentException("candidates \"" + x.name() + "\" and \"" + c.name()
						+ "\" both become \"" + fileSafe(c.name()) + "\" in run ids");
		candidates.add(c);
		return this;
	}

	public Experiment candidates(Candidate... cs) {
		for (Candidate c : cs)
			candidate(c);
		return this;
	}

	/** Runs every candidate under each of these fault models (default: the scenario's). */
	public Experiment faultModels(FaultModel... models) {
		for (FaultModel m : models) {
			for (FaultModel x : faultModels)
				if (fileSafe(x.label()).equals(fileSafe(m.label())))
					throw new IllegalArgumentException("fault models \"" + x.label() + "\" and \"" + m.label()
							+ "\" both become \"" + fileSafe(m.label()) + "\" in run ids");
			faultModels.add(m);
		}
		return this;
	}

	/**
	 * Sweeps a parameter: for each value, {@code apply} modifies a copy of the
	 * scenario's builder. Several dimensions form a cartesian product.
	 */
	@SuppressWarnings("unchecked")
	public <T> Experiment vary(String name, List<T> values, BiFunction<ScenarioBuilder, T, ScenarioBuilder> apply) {
		if (values.isEmpty())
			throw new IllegalArgumentException("no values for " + name);
		dimensions.add(new Dimension(name, new ArrayList<>(values),
				(BiFunction<ScenarioBuilder, Object, ScenarioBuilder>) apply));
		return this;
	}

	public Experiment replications(int n) {
		if (n < 1)
			throw new IllegalArgumentException("replications must be at least 1");
		this.replications = n;
		return this;
	}

	/** The candidate the others are compared against (default: the first). */
	public Experiment baseline(String candidateName) {
		this.baseline = candidateName;
		return this;
	}

	/** The metrics to compare (default {@link Metrics#defaults()}); every built-in is still written to runs.csv. */
	public Experiment metrics(Metric... selected) {
		metrics.addAll(List.of(selected));
		return this;
	}

	/** Whether to draw PNG charts (default true). */
	public Experiment charts(boolean enabled) {
		this.charts = enabled;
		return this;
	}

	/** Whether to silence PureEdgeSim's console output (default true). */
	public Experiment quiet(boolean enabled) {
		this.quiet = enabled;
		return this;
	}

	/** Whether to print one line per run (default true). */
	public Experiment progress(boolean enabled) {
		this.progress = enabled;
		return this;
	}

	/** Run ids may name files: keep letters, digits, '.', '_' and '-', and map the rest to '_'. */
	private static String fileSafe(String name) {
		return name.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	/** Runs the grid and writes the result files into {@code output}. */
	public ExperimentResult run(Path output) {
		if (candidates.isEmpty())
			throw new IllegalStateException("add at least one candidate");
		String base0 = baseline == null ? candidates.get(0).name() : baseline;
		if (candidates.stream().noneMatch(c -> c.name().equals(base0)))
			throw new IllegalArgumentException("unknown baseline \"" + base0 + "\"");
		List<Metric> selected = metrics.isEmpty() ? Metrics.defaults() : List.copyOf(metrics);
		List<Metric> computed = new ArrayList<>(Metrics.all());
		Set<String> names = new LinkedHashSet<>();
		computed.forEach(m -> names.add(m.name()));
		for (Metric m : selected)
			if (names.add(m.name()))
				computed.add(m);

		List<Map<String, String>> paramSets = new ArrayList<>();
		List<Scenario> scenarios = new ArrayList<>();
		expand(0, new LinkedHashMap<>(), base.toBuilder(), paramSets, scenarios);

		try {
			Files.createDirectories(output);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		Path core = output.resolve(".pureedgesim");
		List<RunResult> results = new ArrayList<>();
		int total = scenarios.size() * Math.max(1, faultModels.size()) * replications * candidates.size();
		int done = 0;
		for (int p = 0; p < scenarios.size(); p++) {
			// without explicit fault models, each (possibly swept) scenario keeps its own
			List<FaultModel> models = faultModels.isEmpty() ? List.of(scenarios.get(p).faultModel()) : faultModels;
			for (FaultModel model : models) {
				Scenario s = faultModels.isEmpty() ? scenarios.get(p) : scenarios.get(p).withFaults(model);
				for (int rep = 0; rep < replications; rep++) {
					long workloadSeed = mix(s.seed(), rep, 1);
					long faultSeed = mix(s.seed(), rep, 2);
					for (Candidate c : candidates) {
						long algorithmSeed = mix(s.seed(), rep, 3 + c.name().hashCode());
						String id = String.format(Locale.ROOT, "p%d-%s-r%d-%s", p, fileSafe(model.label()), rep,
								fileSafe(c.name()));
						RunSpec spec = new RunSpec(id, s, c, model.label(), paramSets.get(p), rep, workloadSeed,
								faultSeed, algorithmSeed, core);
						RunData data = Runner.run(spec, quiet);
						Map<String, Double> values = new LinkedHashMap<>();
						for (Metric m : computed)
							values.put(m.name(), m.compute(data));
						RunResult r = new RunResult(data, values);
						results.add(r);
						done++;
						if (progress)
							System.out.printf(Locale.ROOT, "[edgesdk] %d/%d %s: %s%n", done, total, id,
									summary(r, selected));
					}
				}
			}
		}
		ComparisonReport report = ComparisonReport.of(results, selected, base0);
		CsvExporter.writeAll(output, results, computed, report);
		if (charts)
			ComparisonCharts.write(output.resolve("charts"), results, report, selected);
		ExperimentResult result = new ExperimentResult(output, results, selected, report, base0);
		if (progress)
			result.printTable(System.out);
		return result;
	}

	private void expand(int dim, Map<String, String> params, ScenarioBuilder b, List<Map<String, String>> paramSets,
			List<Scenario> out) {
		if (dim == dimensions.size()) {
			paramSets.add(Map.copyOf(params));
			out.add(b.build());
			return;
		}
		Dimension d = dimensions.get(dim);
		for (Object v : d.values()) {
			Map<String, String> next = new LinkedHashMap<>(params);
			next.put(d.name(), String.valueOf(v));
			expand(dim + 1, next, d.apply().apply(b.copy(), v), paramSets, out);
		}
	}

	private static String summary(RunResult r, List<Metric> selected) {
		StringBuilder sb = new StringBuilder();
		for (Metric m : selected.subList(0, Math.min(4, selected.size()))) {
			double v = r.value(m);
			if (!Double.isNaN(v))
				sb.append(sb.length() == 0 ? "" : ", ").append(m.name()).append('=')
						.append(String.format(Locale.ROOT, "%.2f", v));
		}
		return sb.toString();
	}

	/** SplitMix64-style seed derivation. */
	static long mix(long seed, long a, long b) {
		long z = seed + 0x9E3779B97F4A7C15L * (a + 1) + 0xBF58476D1CE4E5B9L * (b + 1);
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
