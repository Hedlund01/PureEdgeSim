package zip.hedlund.pureedgesim.edgesdk.algorithm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * Where an attempt of a task runs: on the device itself, on another node, split
 * into parts that run in parallel on several nodes, or nowhere. An attempt can
 * carry a retry timer: when its result is not back after {@code retryAfter}
 * seconds, the policy is asked again through
 * {@link OrchestrationPolicy#onAttemptTimedOut}. The timer is local to the
 * device, so this is an honest timeout, not a failure notification.
 */
public final class Placement {

	private enum Kind {
		LOCAL, REMOTE, SPLIT, REJECT
	}

	private static final Placement LOCAL = new Placement(Kind.LOCAL, null, Map.of(), 0);
	private static final Placement REJECT = new Placement(Kind.REJECT, null, Map.of(), 0);

	private final Kind kind;
	private final NodeRef node;
	private final Map<NodeRef, Double> shares;
	private final double retryAfter;

	private Placement(Kind kind, NodeRef node, Map<NodeRef, Double> shares, double retryAfter) {
		this.kind = kind;
		this.node = node;
		this.shares = shares;
		this.retryAfter = retryAfter;
	}

	/** Run on the device that created the task. */
	public static Placement local() {
		return LOCAL;
	}

	/** Offload to {@code node}. */
	public static Placement on(NodeRef node) {
		return new Placement(Kind.REMOTE, Objects.requireNonNull(node, "node"), Map.of(), 0);
	}

	/**
	 * Splits the task into independent parts that run in parallel, one per node
	 * (the device itself may take a part). A part gets its share of the task's
	 * length, input and output; the result is back when every part's result is.
	 * <p>
	 * A part that is lost loses the whole attempt: the other parts' results do not
	 * complete it. Set {@link #withRetryAfter} to be asked again; the retry hook
	 * then names the first part still without a result.
	 *
	 * @param shares the share of the task per node; normalised to sum to 1, zero
	 *               shares are left out
	 * @return the split, or {@link #on(NodeRef)} when a single node is left
	 */
	public static Placement split(Map<NodeRef, Double> shares) {
		double sum = 0;
		for (Map.Entry<NodeRef, Double> e : shares.entrySet()) {
			Objects.requireNonNull(e.getKey(), "node");
			double v = e.getValue();
			if (!(v >= 0) || Double.isInfinite(v))
				throw new IllegalArgumentException("share of " + e.getKey() + " must be finite and >= 0, was " + v);
			sum += v;
		}
		if (!(sum > 0))
			throw new IllegalArgumentException("a split needs a positive share");
		Map<NodeRef, Double> normalised = new LinkedHashMap<>();
		for (Map.Entry<NodeRef, Double> e : shares.entrySet())
			if (e.getValue() > 0)
				normalised.put(e.getKey(), e.getValue() / sum);
		if (normalised.size() == 1)
			return on(normalised.keySet().iterator().next());
		return new Placement(Kind.SPLIT, null, Collections.unmodifiableMap(normalised), 0);
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
		return new Placement(kind, node, shares, seconds);
	}

	public boolean isLocal() {
		return kind == Kind.LOCAL;
	}

	public boolean isRejected() {
		return kind == Kind.REJECT;
	}

	/** @return true for a task split over several nodes */
	public boolean isSplit() {
		return kind == Kind.SPLIT;
	}

	/** @return the target node of an offload; empty for local, a split or a rejection */
	public Optional<NodeRef> node() {
		return Optional.ofNullable(node);
	}

	/** @return the share of the task per node of a split, summing to 1; empty otherwise */
	public Map<NodeRef, Double> shares() {
		return shares;
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
		case SPLIT -> "split" + shares;
		} + (retryAfter > 0 ? " retry " + retryAfter + "s" : "");
	}
}
