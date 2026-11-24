package zip.hedlund.pureedgesim.edgesdk.algorithm;

import java.util.Objects;
import java.util.Optional;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * Where an attempt of a task runs: on the device itself, on another node, or
 * nowhere. An attempt can carry a retry timer: when its result is not back after
 * {@code retryAfter} seconds, the policy is asked again through
 * {@link OrchestrationPolicy#onAttemptTimedOut}. The timer is local to the
 * device, so this is an honest timeout, not a failure notification.
 */
public final class Placement {

	private enum Kind {
		LOCAL, REMOTE, REJECT
	}

	private static final Placement LOCAL = new Placement(Kind.LOCAL, null, 0);
	private static final Placement REJECT = new Placement(Kind.REJECT, null, 0);

	private final Kind kind;
	private final NodeRef node;
	private final double retryAfter;

	private Placement(Kind kind, NodeRef node, double retryAfter) {
		this.kind = kind;
		this.node = node;
		this.retryAfter = retryAfter;
	}

	/** Run on the device that created the task. */
	public static Placement local() {
		return LOCAL;
	}

	/** Offload to {@code node}. */
	public static Placement on(NodeRef node) {
		return new Placement(Kind.REMOTE, Objects.requireNonNull(node, "node"), 0);
	}

	/** Do not run this task (or stop retrying it). */
	public static Placement reject() {
		return REJECT;
	}

	/**
	 * @param seconds ask the policy again if no result after this long; 0 disables
	 * @return a copy with the retry timer
	 */
	public Placement withRetryAfter(double seconds) {
		if (kind == Kind.REJECT)
			return this;
		return new Placement(kind, node, seconds);
	}

	public boolean isLocal() {
		return kind == Kind.LOCAL;
	}

	public boolean isRejected() {
		return kind == Kind.REJECT;
	}

	/** @return the target node of an offload */
	public Optional<NodeRef> node() {
		return Optional.ofNullable(node);
	}

	public double retryAfter() {
		return retryAfter;
	}

	@Override
	public String toString() {
		return switch (kind) {
		case LOCAL -> "local";
		case REJECT -> "reject";
		case REMOTE -> "on(" + node + ")";
		} + (retryAfter > 0 ? " retry " + retryAfter + "s" : "");
	}
}
