package zip.hedlund.pureedgesim.edgesdk.model;

import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;

/**
 * The tier of the edge-to-cloud continuum a node belongs to.
 */
public enum Tier {
	/** UAVs, UGVs, sensors, phones: generate tasks and storage operations. */
	END_DEVICE,
	/** Static edge servers: roadside units, micro data centers. */
	EDGE_STATIC,
	/** Edge servers that move: truck-mounted or car compute. */
	EDGE_MOBILE,
	/** Cloud data centers. */
	CLOUD;

	/** @return the PureEdgeSim node type that represents this tier */
	public SimulationParameters.TYPES coreType() {
		return switch (this) {
		case END_DEVICE -> SimulationParameters.TYPES.EDGE_DEVICE;
		case EDGE_STATIC, EDGE_MOBILE -> SimulationParameters.TYPES.EDGE_DATACENTER;
		case CLOUD -> SimulationParameters.TYPES.CLOUD;
		};
	}

	/** @return true for both edge tiers */
	public boolean isEdge() {
		return this == EDGE_STATIC || this == EDGE_MOBILE;
	}
}
