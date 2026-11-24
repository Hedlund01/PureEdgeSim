package zip.hedlund.pureedgesim.edgesdk.metrics;

/** Which way is better for a metric. */
public enum Direction {
	HIGHER_IS_BETTER, LOWER_IS_BETTER;

	/** @return true when {@code a} is better than {@code b} */
	public boolean better(double a, double b) {
		if (Double.isNaN(a))
			return false;
		if (Double.isNaN(b))
			return true;
		return this == HIGHER_IS_BETTER ? a > b : a < b;
	}
}
