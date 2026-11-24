package zip.hedlund.pureedgesim.edgesdk.network;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import zip.hedlund.pureedgesim.edgesdk.engine.SdkNode;

/**
 * Data in flight between two nodes, forwarded hop by hop (store and forward). At
 * every node the next hop is chosen from the live topology, so a transfer follows
 * the network as it changes.
 */
public final class Transfer {

	/** What the data is, for accounting. */
	public enum Kind {
		TASK_INPUT, TASK_RESULT, CONTROL, STORAGE
	}

	final long id;
	final SdkNode src;
	final SdkNode dst;
	final long bytes;
	final Kind kind;
	final Consumer<Transfer> onDelivered;
	final BiConsumer<Transfer, LossReason> onLost;
	double remainingBits;
	SdkNode at;
	boolean lost;
	boolean delivered;
	int hops;

	Transfer(long id, SdkNode src, SdkNode dst, long bytes, Kind kind, Consumer<Transfer> onDelivered,
			BiConsumer<Transfer, LossReason> onLost) {
		this.id = id;
		this.src = src;
		this.dst = dst;
		this.bytes = Math.max(1, bytes);
		this.kind = kind;
		this.onDelivered = onDelivered;
		this.onLost = onLost;
		this.at = src;
	}

	public double bits() {
		return bytes * 8.0;
	}

	public long bytes() {
		return bytes;
	}

	public Kind kind() {
		return kind;
	}

	public SdkNode source() {
		return src;
	}

	public SdkNode destination() {
		return dst;
	}

	public int hops() {
		return hops;
	}
}
