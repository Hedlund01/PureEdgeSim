package zip.hedlund.pureedgesim.edgesdk.storage;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * A client write.
 *
 * @param id         unique id within the run; becomes {@link VersionedValue#writeId()}
 * @param key        the key
 * @param valueBytes size of the value
 * @param client     the end device issuing it
 * @param issuedAt   issue time
 * @param deadline   absolute time by which it must be acknowledged
 */
public record WriteRequest(long id, String key, long valueBytes, NodeRef client, double issuedAt, double deadline) {

	/** @return the version this write creates, with the given clock */
	public VersionedValue version(VectorClock clock) {
		return new VersionedValue(key, id, clock, issuedAt, client, valueBytes);
	}
}
