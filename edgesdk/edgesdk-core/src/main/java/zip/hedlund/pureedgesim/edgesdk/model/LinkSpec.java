package zip.hedlund.pureedgesim.edgesdk.model;

import java.util.Objects;

/**
 * A link between two nodes over one channel. It carries traffic in both
 * directions: {@code a → b} at the channel's uplink rate and {@code b → a} at its
 * downlink rate. Two nodes can have several links over different channels.
 *
 * @param a       the node nearer to the end devices
 * @param b       the other node
 * @param channel the channel
 */
public record LinkSpec(NodeRef a, NodeRef b, ChannelSpec channel) {

	public LinkSpec {
		Objects.requireNonNull(a, "a");
		Objects.requireNonNull(b, "b");
		Objects.requireNonNull(channel, "channel");
		if (a.equals(b))
			throw new IllegalArgumentException("a link needs two different nodes: " + a);
	}

	/** @return the identifier of this link */
	public LinkRef ref() {
		return new LinkRef(a.name(), b.name(), channel.name());
	}

	/** @return true when the node is one of the endpoints */
	public boolean touches(NodeRef node) {
		return a.equals(node) || b.equals(node);
	}

	/** @return the other endpoint */
	public NodeRef other(NodeRef node) {
		if (a.equals(node))
			return b;
		if (b.equals(node))
			return a;
		throw new IllegalArgumentException(node + " is not an endpoint of " + ref());
	}
}
