package zip.hedlund.pureedgesim.edgesdk.api;

import java.util.Map;

import zip.hedlund.pureedgesim.edgesdk.metrics.Metric;
import zip.hedlund.pureedgesim.edgesdk.metrics.RunData;

/**
 * The result of one run: everything recorded, and the metric values.
 *
 * @param data    the raw records
 * @param metrics metric name to value (NaN when undefined)
 */
public record RunResult(RunData data, Map<String, Double> metrics) {

	public RunResult {
		metrics = Map.copyOf(metrics);
	}

	public double value(Metric metric) {
		return value(metric.name());
	}

	public double value(String metricName) {
		Double v = metrics.get(metricName);
		if (v == null)
			throw new IllegalArgumentException("metric \"" + metricName + "\" was not computed; known: "
					+ metrics.keySet());
		return v;
	}

	public String candidate() {
		return data.candidate();
	}

	public String condition() {
		return data.condition();
	}
}
