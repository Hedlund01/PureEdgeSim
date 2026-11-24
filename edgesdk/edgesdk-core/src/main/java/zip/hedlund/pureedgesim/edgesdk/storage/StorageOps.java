package zip.hedlund.pureedgesim.edgesdk.storage;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * The local key-value store of each node, and the way to answer clients. Only
 * {@code storeLocal}/{@code local} touch data, and only on one node: moving data
 * between nodes is done with messages through the {@code SdkContext}, so it costs
 * bandwidth and can be lost.
 */
public interface StorageOps {

	/** @return the nodes that host storage (design information) */
	List<NodeRef> storageNodes();

	/** Stores a version on {@code node}, replacing what it held for the key. Ignored if the node is down. */
	void storeLocal(NodeRef node, VersionedValue value);

	/** @return what {@code node} holds for the key */
	Optional<VersionedValue> local(NodeRef node, String key);

	/** @return the keys {@code node} holds */
	Set<String> keys(NodeRef node);

	/**
	 * Answers a write at its client. Call it when the client learns the outcome
	 * (usually in a reply callback at the client). Only the first answer counts,
	 * and only if it comes before the deadline.
	 *
	 * @param ok whether the write succeeded
	 */
	void ack(WriteRequest write, boolean ok);

	/** Answers a read at its client; empty means "not found" or failure. */
	void reply(ReadRequest read, Optional<VersionedValue> value);
}
