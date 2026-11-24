package zip.hedlund.pureedgesim.edgesdk.storage;

import zip.hedlund.pureedgesim.edgesdk.algorithm.Algorithm;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * A distributed storage or replication strategy. Clients (end devices) issue
 * reads and writes from the scenario's {@link StorageWorkload}; the strategy
 * decides where data lives and how replicas are kept in sync, using messages
 * ({@code ctx.send}/{@code ctx.request}), and answers each operation with
 * {@link StorageOps#ack} or {@link StorageOps#reply}. An operation not answered
 * by its deadline counts as unavailable.
 */
public abstract class StorageStrategy extends Algorithm {

	/** The node stores; set by the engine before {@code onStart}. */
	protected StorageOps ops;

	/** Binds the stores; called by the engine. */
	public final void bindStorage(StorageOps storage) {
		this.ops = storage;
	}

	/** A client issued a write, at node {@code at} (the client). */
	public abstract void onWrite(NodeRef at, WriteRequest write, StorageOps ops);

	/** A client issued a read, at node {@code at} (the client). */
	public abstract void onRead(NodeRef at, ReadRequest read, StorageOps ops);
}
