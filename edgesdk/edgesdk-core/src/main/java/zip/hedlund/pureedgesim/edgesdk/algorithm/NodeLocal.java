package zip.hedlund.pureedgesim.edgesdk.algorithm;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * State kept separately on every node, and wiped when that node crashes.
 *
 * @param <T> the state type
 */
public interface NodeLocal<T> {

	/** @return the node's value, created on first use */
	T at(NodeRef node);

	/** Replaces the node's value. */
	void set(NodeRef node, T value);
}
