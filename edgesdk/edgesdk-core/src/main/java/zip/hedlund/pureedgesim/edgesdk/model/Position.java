package zip.hedlund.pureedgesim.edgesdk.model;

/**
 * A point on the simulation map, in metres.
 */
public record Position(double x, double y) {

	public static Position of(double x, double y) {
		return new Position(x, y);
	}

	public double distanceTo(Position other) {
		return Math.hypot(x - other.x, y - other.y);
	}
}
