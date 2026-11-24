package zip.hedlund.pureedgesim.edgesdk.metrics;

/**
 * Custom figures an algorithm publishes. They end up in {@code runs.csv} as
 * {@code custom.<name>} (counters) and {@code custom.<name>.mean} (samples), and
 * can be compared like built-in metrics with {@link Metrics#custom(String)}.
 */
public interface MetricsCollector {

	/** Adds {@code delta} to a counter. */
	void add(String name, double delta);

	/** Records one sample of a value; the run reports the mean. */
	void record(String name, double value);
}
