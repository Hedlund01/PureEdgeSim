package partitiontestbed;

import java.util.Collections;
import java.util.Set;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

/**
 * An edge server of the continuum, with the roles the testbed gives it.
 * <p>
 * An access server is one that end devices attach to: it is their only way into
 * the network, and the server that decides, through the strategy, where their
 * services run. A gateway has an uplink to the cloud data center: the only way
 * to reach it. A server can be both, either, or neither.
 */
public final class EdgeServer {

	/** The edge data center of the framework. */
	public final ComputingNode node;
	/** Traits the server advertises. */
	public final Set<Trait> traits;
	/** Whether devices attach to this server. */
	public final boolean access;
	/** Whether this server has an uplink to the cloud. */
	public final boolean gateway;
	/** Whether this server moves, as one built into a vehicle. */
	public final boolean mobile;

	EdgeServer(ComputingNode node, Set<Trait> traits, boolean access, boolean gateway, boolean mobile) {
		this.mobile = mobile;
		this.node = node;
		this.traits = Collections.unmodifiableSet(traits);
		this.access = access;
		this.gateway = gateway;
	}

	/** @return the name of the server, {@code dc1} to {@code dcN} */
	public String name() {
		return node.getName();
	}

	@Override
	public String toString() {
		return name();
	}
}
