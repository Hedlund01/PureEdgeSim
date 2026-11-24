package zip.hedlund.pureedgesim.edgesdk.engine;

import java.util.Map;
import java.util.TreeMap;

import zip.hedlund.pureedgesim.edgesdk.metrics.MetricsCollector;

/** Counters and samples published by algorithms. */
final class CustomMetrics implements MetricsCollector {

	private final Map<String, Double> counters = new TreeMap<>();
	private final Map<String, double[]> samples = new TreeMap<>();

	@Override
	public void add(String name, double delta) {
		counters.merge(name, delta, Double::sum);
	}

	@Override
	public void record(String name, double value) {
		double[] s = samples.computeIfAbsent(name, k -> new double[2]);
		s[0] += value;
		s[1]++;
	}

	Map<String, Double> snapshot() {
		Map<String, Double> out = new TreeMap<>(counters);
		samples.forEach((k, v) -> out.put(k + ".mean", v[1] == 0 ? Double.NaN : v[0] / v[1]));
		return out;
	}
}
