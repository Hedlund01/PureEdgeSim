package zip.hedlund.pureedgesim.edgesdk.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mechalikh.pureedgesim.locationmanager.DefaultMobilityModel;
import com.mechalikh.pureedgesim.locationmanager.GaussMarkovMobilityModel;
import com.mechalikh.pureedgesim.locationmanager.MobilityModel;
import com.mechalikh.pureedgesim.locationmanager.RandomWalkMobilityModel;
import com.mechalikh.pureedgesim.locationmanager.ReferencePointGroupMobilityModel;

/**
 * How a node moves. Any PureEdgeSim {@link MobilityModel} with the usual
 * {@code (SimulationManager, Location)} constructor can be used; its options are
 * passed through {@link MobilityModel#setParameters(Map)}.
 * <p>
 * Each node draws its speed uniformly in {@code [minSpeed, maxSpeed]} m/s from the
 * workload random stream, so the paths are the same for every algorithm compared.
 *
 * @param model    the mobility model class, or null for a static node
 * @param minSpeed lowest speed in m/s
 * @param maxSpeed highest speed in m/s
 * @param options  model options (e.g. {@code group} for group mobility)
 */
public record MobilitySpec(Class<? extends MobilityModel> model, double minSpeed, double maxSpeed,
		Map<String, String> options) {

	/** A node that never moves. */
	public static final MobilitySpec STATIC = new MobilitySpec(null, 0, 0, Map.of());

	public MobilitySpec {
		if (minSpeed < 0 || maxSpeed < minSpeed)
			throw new IllegalArgumentException("need 0 <= minSpeed <= maxSpeed");
		options = Map.copyOf(options == null ? Map.of() : options);
	}

	/** @return true when the node moves */
	public boolean isMobile() {
		return model != null && maxSpeed > 0;
	}

	/** Random walk: a new random direction every second. */
	public static MobilitySpec randomWalk(double minSpeed, double maxSpeed) {
		return new MobilitySpec(RandomWalkMobilityModel.class, minSpeed, maxSpeed, Map.of());
	}

	/** Gauss-Markov: smooth, correlated direction and speed (good for UAVs). */
	public static MobilitySpec gaussMarkov(double minSpeed, double maxSpeed) {
		return new MobilitySpec(GaussMarkovMobilityModel.class, minSpeed, maxSpeed, Map.of());
	}

	/** Move straight for a while, pause, turn: vehicles and ground robots. */
	public static MobilitySpec randomWaypoint(double minSpeed, double maxSpeed) {
		return new MobilitySpec(DefaultMobilityModel.class, minSpeed, maxSpeed, Map.of());
	}

	/**
	 * Reference-point group mobility: every node of the same group follows one
	 * shared reference point, keeping its offset. Use it to keep a swarm around its
	 * truck.
	 */
	public static MobilitySpec group(String group, double anchorSpeed) {
		return new MobilitySpec(ReferencePointGroupMobilityModel.class, anchorSpeed, anchorSpeed,
				Map.of("group", group, "anchor_speed", Double.toString(anchorSpeed)));
	}

	public MobilitySpec withSpeed(double min, double max) {
		return new MobilitySpec(model, min, max, options);
	}

	public MobilitySpec withOption(String key, String value) {
		Map<String, String> copy = new LinkedHashMap<>(options);
		copy.put(key, value);
		return new MobilitySpec(model, minSpeed, maxSpeed, copy);
	}
}
