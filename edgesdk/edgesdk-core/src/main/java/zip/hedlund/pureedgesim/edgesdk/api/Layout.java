package zip.hedlund.pureedgesim.edgesdk.api;

import java.util.Objects;

/**
 * Where a node starts on the map. Random positions are drawn from the scenario
 * seed, so the layout is the same for every run of a scenario.
 */
public sealed interface Layout {

	/** A fixed position, in metres. */
	record At(double x, double y) implements Layout {
	}

	/** Uniformly random on the map. */
	record Anywhere() implements Layout {
	}

	/** Uniformly random within {@code radius} metres of another node. */
	record Near(String anchor, double radius) implements Layout {
		public Near {
			Objects.requireNonNull(anchor, "anchor");
			if (radius < 0)
				throw new IllegalArgumentException("radius must be >= 0");
		}
	}

	static Layout at(double x, double y) {
		return new At(x, y);
	}

	static Layout random() {
		return new Anywhere();
	}

	static Layout near(String anchor, double radius) {
		return new Near(anchor, radius);
	}
}
