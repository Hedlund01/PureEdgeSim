package zip.hedlund.pureedgesim.edgesdk.storage;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * A version of a key. Values carry no content: the SDK only tracks which write
 * produced them, which is what consistency metrics need.
 *
 * @param key       the key
 * @param writeId   id of the write that produced this version (unique per run)
 * @param clock     vector clock, maintained by the strategy (may stay empty)
 * @param writtenAt time the client issued the write
 * @param writer    the client that issued it
 * @param bytes     size of the value
 */
public record VersionedValue(String key, long writeId, VectorClock clock, double writtenAt, NodeRef writer,
		long bytes) {

	/** @return a copy with another clock */
	public VersionedValue withClock(VectorClock newClock) {
		return new VersionedValue(key, writeId, newClock, writtenAt, writer, bytes);
	}

	/**
	 * Last-writer-wins order: the later write, then the larger id.
	 *
	 * @return true when this version wins over {@code other} (or other is null)
	 */
	public boolean newerThan(VersionedValue other) {
		if (other == null)
			return true;
		if (writtenAt != other.writtenAt)
			return writtenAt > other.writtenAt;
		return writeId > other.writeId;
	}

	/** @return the newer of two versions by {@link #newerThan} (nulls allowed) */
	public static VersionedValue lww(VersionedValue a, VersionedValue b) {
		if (a == null)
			return b;
		return a.newerThan(b) ? a : b;
	}
}
