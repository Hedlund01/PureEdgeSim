package zip.hedlund.pureedgesim.edgesdk.detect;

import java.util.Collection;
import java.util.Set;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * A failure detector that runs at nodes and learns only from its own messages.
 * Implementations probe targets with {@link Probe} requests; any running node
 * answers them by default (see {@code Algorithm.onRequest}). The probes cost
 * bandwidth like any message and are counted as control overhead.
 */
public interface FailureDetector {

	/** The heartbeat request. */
	record Probe() {
	}

	/** The heartbeat reply. */
	record Ack() {
	}

	Probe PROBE = new Probe();
	Ack ACK = new Ack();

	/** Probe size on the wire, bytes. */
	long PROBE_BYTES = 32;

	/**
	 * Starts monitoring {@code targets} from node {@code at}. Call it again after
	 * the node restarts ({@code onNodeRestarted}): a crash wipes the detector state.
	 */
	void monitor(NodeRef at, Collection<NodeRef> targets);

	/** @return true when {@code at} currently suspects {@code target} */
	boolean suspects(NodeRef at, NodeRef target);

	/** @return the targets {@code at} currently suspects */
	Set<NodeRef> suspected(NodeRef at);

	/** Registers a callback for every change of belief. */
	void onChange(Listener listener);

	/** Called when {@code at} starts or stops suspecting {@code target}. */
	@FunctionalInterface
	interface Listener {
		void changed(NodeRef at, NodeRef target, boolean suspected);
	}
}
