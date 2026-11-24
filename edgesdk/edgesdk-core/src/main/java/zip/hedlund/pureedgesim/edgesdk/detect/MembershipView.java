package zip.hedlund.pureedgesim.edgesdk.detect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * A node's view of which peers are alive, from a failure detector: the peers it
 * monitors minus those it suspects, in a fixed preference order.
 */
public final class MembershipView {

	private final FailureDetector detector;
	private final NodeRef at;
	private final List<NodeRef> peers;

	public MembershipView(FailureDetector detector, NodeRef at, Collection<NodeRef> peersInPreferenceOrder) {
		this.detector = detector;
		this.at = at;
		this.peers = List.copyOf(peersInPreferenceOrder);
		detector.monitor(at, peers);
	}

	/** @return the peers not suspected, in preference order */
	public List<NodeRef> alive() {
		List<NodeRef> out = new ArrayList<>();
		for (NodeRef p : peers)
			if (!detector.suspects(at, p))
				out.add(p);
		return out;
	}

	public List<NodeRef> peers() {
		return peers;
	}
}
