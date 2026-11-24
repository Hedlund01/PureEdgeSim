package zip.hedlund.pureedgesim.edgesdk.model;

import java.util.Objects;

/**
 * A node of a scenario: its name, its spec, and where it starts.
 *
 * @param ref      the node's name
 * @param spec     hardware, power, mobility and channels
 * @param position initial position on the map
 */
public record NodeDecl(NodeRef ref, NodeSpec spec, Position position) {

	public NodeDecl {
		Objects.requireNonNull(ref, "ref");
		Objects.requireNonNull(spec, "spec");
		Objects.requireNonNull(position, "position");
	}

	public String name() {
		return ref.name();
	}

	public Tier tier() {
		return spec.tier();
	}
}
