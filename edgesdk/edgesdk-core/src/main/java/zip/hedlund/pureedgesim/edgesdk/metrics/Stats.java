package zip.hedlund.pureedgesim.edgesdk.metrics;

import java.util.Arrays;
import java.util.Collection;

/**
 * Small statistics helpers.
 */
public final class Stats {

	/** Two-sided 95 % Student t quantiles for 1..30 degrees of freedom. */
	private static final double[] T95 = { 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
			2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060,
			2.056, 2.052, 2.048, 2.045, 2.042 };

	private Stats() {
	}

	/** @return the values without NaNs */
	public static double[] finite(Collection<Double> values) {
		return values.stream().filter(v -> v != null && !v.isNaN()).mapToDouble(Double::doubleValue).toArray();
	}

	public static double mean(double[] v) {
		return v.length == 0 ? Double.NaN : Arrays.stream(v).average().orElse(Double.NaN);
	}

	/** Sample standard deviation; NaN below two values. */
	public static double std(double[] v) {
		if (v.length < 2)
			return Double.NaN;
		double m = mean(v);
		double s = 0;
		for (double x : v)
			s += (x - m) * (x - m);
		return Math.sqrt(s / (v.length - 1));
	}

	/** Half-width of the 95 % confidence interval of the mean; NaN below two values. */
	public static double ci95(double[] v) {
		if (v.length < 2)
			return Double.NaN;
		int df = v.length - 1;
		return t95(df) * std(v) / Math.sqrt(v.length);
	}

	/**
	 * Two-sided 95 % Student t quantile: the table up to 30 degrees of freedom,
	 * beyond it the Cornish-Fisher expansion around the normal quantile
	 * (Abramowitz &amp; Stegun 26.7.5), accurate to 1e-5 there.
	 */
	static double t95(int df) {
		if (df <= T95.length)
			return T95[df - 1];
		double z = 1.959963984540054;
		double z2 = z * z, z3 = z2 * z, z5 = z3 * z2, z7 = z5 * z2, z9 = z7 * z2;
		double n = df;
		return z + (z3 + z) / (4 * n) + (5 * z5 + 16 * z3 + 3 * z) / (96 * n * n)
				+ (3 * z7 + 19 * z5 + 17 * z3 - 15 * z) / (384 * n * n * n)
				+ (79 * z9 + 776 * z7 + 1482 * z5 - 1920 * z3 - 945 * z) / (92160 * n * n * n * n);
	}

	/** Percentile by linear interpolation, p in [0, 100]; NaN when empty. */
	public static double percentile(double[] v, double p) {
		if (v.length == 0)
			return Double.NaN;
		double[] s = v.clone();
		Arrays.sort(s);
		double rank = p / 100.0 * (s.length - 1);
		int lo = (int) Math.floor(rank);
		int hi = (int) Math.ceil(rank);
		return s[lo] + (s[hi] - s[lo]) * (rank - lo);
	}

	/** @return 100 * part / total, or NaN when total is 0 */
	public static double pct(long part, long total) {
		return total == 0 ? Double.NaN : 100.0 * part / total;
	}
}
