package zip.hedlund.pureedgesim.edgesdk.algorithm;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * Base class of everything a developer plugs in: offloading policies
 * ({@link OrchestrationPolicy}) and storage strategies
 * ({@code StorageStrategy}).
 * <p>
 * <b>Information boundary.</b> An algorithm is never told about faults. It runs
 * as if it were deployed on every node: each handler is invoked <em>at</em> a node
 * and should only use what that node could know: the design topology
 * ({@link SdkContext#topology()}), the node's own state ({@link SdkContext#local}),
 * the messages it receives, the replies to its requests and its own timeouts.
 * Failure detection is the algorithm's job; the {@code detect} package has
 * building blocks.
 * <p>
 * Keep per-node state in {@link NodeLocal} (from {@link SdkContext#nodeLocal}),
 * so that a crash wipes it as a reboot would. A fresh instance is created for
 * every run, so plain fields are fine for configuration.
 */
public abstract class Algorithm {

	/** The context of the current run, set before {@link #onStart(SdkContext)}. */
	protected SdkContext ctx;

	/**
	 * Binds the context. Called by the engine before {@link #onStart(SdkContext)};
	 * not meant to be called by developers.
	 *
	 * @param context the run context
	 */
	public final void bind(SdkContext context) {
		this.ctx = context;
	}

	/**
	 * Called once at time 0, after the nodes exist and before any task. Start
	 * timers and protocols here.
	 *
	 * @param ctx the run context (also available as the {@link #ctx} field)
	 */
	public void onStart(SdkContext ctx) {
	}

	/**
	 * A message sent with {@link SdkContext#send} reached node {@code at}.
	 *
	 * @param at      the receiving node
	 * @param from    the sender
	 * @param payload what was sent
	 */
	public void onMessage(NodeRef at, NodeRef from, Object payload) {
	}

	/**
	 * A request sent with {@link SdkContext#request} reached node {@code at}.
	 * <p>
	 * By default, a running node answers the heartbeat probes of the
	 * {@code detect} package; override this method and call
	 * {@code super.onRequest} for requests you do not handle.
	 *
	 * @return the reply, sent back over the network; a {@link Response} to set its
	 *         size; a {@link Deferred} to answer later; {@code null} to send nothing
	 *         (the requester will time out)
	 */
	public Object onRequest(NodeRef at, NodeRef from, Object request) {
		if (request instanceof zip.hedlund.pureedgesim.edgesdk.detect.FailureDetector.Probe)
			return zip.hedlund.pureedgesim.edgesdk.detect.FailureDetector.ACK;
		return null;
	}

	/**
	 * Node {@code at} restarted after a crash. Its {@link NodeLocal} state is
	 * empty and its timers are gone. This is local knowledge: the node knows it
	 * rebooted; no other node is told.
	 */
	public void onNodeRestarted(NodeRef at) {
	}

	/** Called once when the run ends. */
	public void onStop() {
	}

	/** @return a short name used in logs; the class name by default */
	public String name() {
		return getClass().getSimpleName();
	}
}
