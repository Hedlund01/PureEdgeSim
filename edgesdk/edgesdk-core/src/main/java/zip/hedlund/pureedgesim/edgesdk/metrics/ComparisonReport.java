package zip.hedlund.pureedgesim.edgesdk.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import zip.hedlund.pureedgesim.edgesdk.api.RunResult;

/**
 * Candidates compared metric by metric, per condition (fault model and swept
 * parameters): mean, spread, 95 % confidence interval, difference with the
 * baseline, and which candidate is best.
 */
public final class ComparisonReport {

	/**
	 * One cell of the comparison.
	 *
	 * @param condition fault model label and parameter values
	 * @param candidate candidate name
	 * @param metric    metric name
	 * @param unit      metric unit
	 * @param direction which way is better
	 * @param n         runs with a defined value
	 * @param mean      mean over the replications
	 * @param std       standard deviation
	 * @param ci95      half-width of the 95 % confidence interval
	 * @param delta     mean minus the baseline's mean
	 * @param deltaPct  delta in percent of the baseline's mean
	 * @param best      best mean of the condition for this metric
	 */
	public record Row(String condition, String candidate, String metric, String unit, Direction direction, int n,
			double mean, double std, double ci95, double delta, double deltaPct, boolean best) {
	}

	private final List<Row> rows;
	private final List<String> conditions;

	private ComparisonReport(List<Row> rows, List<String> conditions) {
		this.rows = List.copyOf(rows);
		this.conditions = List.copyOf(conditions);
	}

	/** @return the condition key of a run: fault model label and parameters */
	public static String conditionOf(RunData d) {
		if (d.params().isEmpty())
			return d.condition();
		return d.condition() + " " + new java.util.TreeMap<>(d.params()).entrySet().stream()
				.map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" "));
	}

	public static ComparisonReport of(List<RunResult> runs, List<Metric> metrics, String baseline) {
		Map<String, Map<String, List<RunResult>>> grouped = new LinkedHashMap<>();
		for (RunResult r : runs)
			grouped.computeIfAbsent(conditionOf(r.data()), k -> new LinkedHashMap<>())
					.computeIfAbsent(r.candidate(), k -> new ArrayList<>()).add(r);
		List<Row> rows = new ArrayList<>();
		for (Map.Entry<String, Map<String, List<RunResult>>> cond : grouped.entrySet()) {
			for (Metric m : metrics) {
				Map<String, double[]> values = new LinkedHashMap<>();
				for (Map.Entry<String, List<RunResult>> c : cond.getValue().entrySet())
					values.put(c.getKey(), c.getValue().stream().mapToDouble(r -> r.value(m))
							.filter(v -> !Double.isNaN(v)).toArray());
				double baseMean = values.containsKey(baseline) ? Stats.mean(values.get(baseline)) : Double.NaN;
				String best = null;
				double bestMean = Double.NaN;
				for (Map.Entry<String, double[]> e : values.entrySet()) {
					double mean = Stats.mean(e.getValue());
					if (m.direction().better(mean, bestMean)) {
						best = e.getKey();
						bestMean = mean;
					}
				}
				final double top = bestMean;
				long ties = values.values().stream().filter(x -> Stats.mean(x) == top).count();
				for (Map.Entry<String, double[]> e : values.entrySet()) {
					double[] v = e.getValue();
					double mean = Stats.mean(v);
					double delta = mean - baseMean;
					double pct = baseMean == 0 || Double.isNaN(baseMean) ? Double.NaN : 100 * delta / Math.abs(baseMean);
					boolean isBest = e.getKey().equals(best) && values.size() > 1 && ties == 1;
					rows.add(new Row(cond.getKey(), e.getKey(), m.name(), m.unit(), m.direction(), v.length, mean,
							Stats.std(v), Stats.ci95(v), delta, pct, isBest));
				}
			}
		}
		return new ComparisonReport(rows, new ArrayList<>(grouped.keySet()));
	}

	public List<Row> rows() {
		return rows;
	}

	public List<String> conditions() {
		return conditions;
	}

	public Optional<Row> row(String condition, String candidate, String metric) {
		return rows.stream().filter(
				r -> r.condition().equals(condition) && r.candidate().equals(candidate) && r.metric().equals(metric))
				.findFirst();
	}

	/** @return candidate names in order of appearance */
	public List<String> candidates() {
		Set<String> s = new LinkedHashSet<>();
		rows.forEach(r -> s.add(r.candidate()));
		return new ArrayList<>(s);
	}
}
