package zip.hedlund.pureedgesim.edgesdk.algorithm;

import java.util.function.BiConsumer;

/**
 * A reply that is sent later. Return one from {@link Algorithm#onRequest} when the
 * answer needs more messages first (e.g. a primary that waits for its backups),
 * then call {@link #reply} once. If the node crashes before, nothing is sent.
 */
public final class Deferred {

	private BiConsumer<Object, Long> sink;
	private Object payload;
	private long bytes;
	private boolean replied;

	/** Sends the reply with the default size. */
	public void reply(Object reply) {
		reply(reply, Response.DEFAULT_BYTES);
	}

	/** Sends the reply; only the first call counts. */
	public void reply(Object reply, long size) {
		if (replied)
			return;
		replied = true;
		this.payload = reply;
		this.bytes = size;
		if (sink != null)
			sink.accept(reply, size);
	}

	public boolean isReplied() {
		return replied;
	}

	/** Connects the reply to the network; called by the engine. */
	public void connect(BiConsumer<Object, Long> target) {
		this.sink = target;
		if (replied)
			target.accept(payload, bytes);
	}
}
