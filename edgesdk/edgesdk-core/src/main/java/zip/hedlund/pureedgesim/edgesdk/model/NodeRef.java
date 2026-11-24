package zip.hedlund.pureedgesim.edgesdk.model;

import java.util.Objects;

/**
 * The name of a node, which is how algorithms address nodes.
 */
public record NodeRef(String name) implements Comparable<NodeRef> {

	public NodeRef {
		Objects.requireNonNull(name, "name");
	}

	public static NodeRef of(String name) {
		return new NodeRef(name);
	}

	@Override
	public int compareTo(NodeRef o) {
		return name.compareTo(o.name);
	}

	@Override
	public String toString() {
		return name;
	}
}
