package zip.hedlund.pureedgesim.edgesdk.metrics;

import java.util.function.ToDoubleFunction;

/**
 * A figure computed from a finished run. The SDK has a catalogue of built-in
 * metrics in {@link Metrics}; define your own as a lambda:
 *
 * <pre>{@code
 * Metric cloudShare = Metric.of("cloud_share_pct", "%", Direction.LOWER_IS_BETTER,
 *         run -> 100.0 * run.tasks().stream().filter(t -> t.executedOn().startsWith("cloud")).count()
 *                     / Math.max(1, run.tasks().size()));
 * }</pre>
 */
public interface Metric {

	/** @return a unique name, used as the CSV column */
	String name();

	/** @return the unit, for reports */
	String unit();

	Direction direction();

	/** @return the value for one run; NaN when undefined (e.g. no partition happened) */
	double compute(RunData run);

	static Metric of(String name, String unit, Direction direction, ToDoubleFunction<RunData> f) {
		return new Metric() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public String unit() {
				return unit;
			}

			@Override
			public Direction direction() {
				return direction;
			}

			@Override
			public double compute(RunData run) {
				return f.applyAsDouble(run);
			}

			@Override
			public String toString() {
				return name;
			}
		};
	}
}
