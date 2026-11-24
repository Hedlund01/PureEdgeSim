package zip.hedlund.pureedgesim.edgesdk.network;

import java.util.Map;

import zip.hedlund.pureedgesim.edgesdk.model.LinkRef;
import zip.hedlund.pureedgesim.edgesdk.model.LinkSpec;

/**
 * A link at run time. Each link fails, degrades and recovers on its own. This is
 * ground truth for fault models, metrics and tests; algorithms only see the
 * {@link LinkSpec} through the design topology.
 */
public interface Link {

	LinkRef ref();

	LinkSpec spec();

	LinkState state();

	/** @return the active down causes and how many faults hold each */
	Map<DownCause, Integer> downCauses();

	/** @return the combined active degradation ({@link Degradation#NONE} if none) */
	Degradation degradation();

	default boolean isUp() {
		return state() != LinkState.DOWN;
	}
}
