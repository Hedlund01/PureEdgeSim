package zip.hedlund.pureedgesim.edgesdk.detect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import zip.hedlund.pureedgesim.edgesdk.algorithm.Cancellable;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Declaration;
import zip.hedlund.pureedgesim.edgesdk.algorithm.NodeLocal;
import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * The phi accrual failure detector (Hayashibara et al., 2004): it keeps the
 * inter-arrival times of the answers to its probes and suspects a target when
 * {@code phi = -log10(P(no answer for this long))} exceeds a threshold, assuming
 * normally distributed intervals.
 */
public final class PhiAccrualDetector implements FailureDetector {

	private static final class History {
		final ArrayDeque<Double> intervals = new ArrayDeque<>();
		double last = Double.NaN;
	}

	private static final class State {
		final Map<NodeRef, History> history = new HashMap<>();
		final Set<NodeRef> suspected = new TreeSet<>();
		final List<Cancellable> timers = new ArrayList<>();
	}

	private final SdkContext ctx;
	private final double period;
	private final double threshold;
	private final int window;
	private final NodeLocal<State> state;
	private final List<Listener> listeners = new ArrayList<>();

	/**
	 * @param period    seconds between probes
	 * @param threshold phi above which a target is suspected (8 is common)
	 * @param window    number of intervals kept
	 */
	public PhiAccrualDetector(SdkContext ctx, double period, double threshold, int window) {
		this.ctx = ctx;
		this.period = period;
		this.threshold = threshold;
		this.window = window;
		this.state = ctx.nodeLocal(n -> new State());
	}

	@Override
	public void monitor(NodeRef at, Collection<NodeRef> targets) {
		State s = state.at(at);
		s.timers.forEach(Cancellable::cancel);
		s.timers.clear();
		Set<NodeRef> set = new LinkedHashSet<>(targets);
		set.remove(at);
		for (NodeRef t : set)
			s.history.put(t, new History());
		s.timers.add(ctx.every(at, period, () -> {
			for (NodeRef t : set) {
				ctx.request(at, t, PROBE_BYTES, PROBE, period * 10, reply -> heard(at, t), () -> {
				});
				evaluate(at, t);
			}
		}));
	}

	private void heard(NodeRef at, NodeRef target) {
		History h = state.at(at).history.computeIfAbsent(target, k -> new History());
		double now = ctx.now();
		if (!Double.isNaN(h.last)) {
			h.intervals.addLast(now - h.last);
			if (h.intervals.size() > window)
				h.intervals.removeFirst();
		}
		h.last = now;
		evaluate(at, target);
	}

	/** @return the current phi of a target as seen from {@code at} */
	public double phi(NodeRef at, NodeRef target) {
		History h = state.at(at).history.get(target);
		if (h == null || Double.isNaN(h.last) || h.intervals.size() < 2)
			return 0;
		double mean = h.intervals.stream().mapToDouble(Double::doubleValue).average().orElse(period);
		double var = h.intervals.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (h.intervals.size() - 1);
		double std = Math.max(Math.sqrt(var), period / 10);
		double elapsed = ctx.now() - h.last;
		double y = (elapsed - mean) / std;
		// logistic approximation of the normal tail
		double e = Math.exp(-y * (1.5976 + 0.070566 * y * y));
		double p = elapsed > mean ? e / (1 + e) : 1 - 1 / (1 + e);
		return -Math.log10(Math.max(p, 1e-300));
	}

	private void evaluate(NodeRef at, NodeRef target) {
		State s = state.at(at);
		boolean suspect = phi(at, target) > threshold;
		if (suspect && s.suspected.add(target))
			changed(at, target, true);
		else if (!suspect && s.suspected.remove(target))
			changed(at, target, false);
	}

	private void changed(NodeRef at, NodeRef target, boolean suspected) {
		ctx.declare(suspected ? Declaration.suspect(at, target) : Declaration.trust(at, target));
		for (Listener l : listeners)
			l.changed(at, target, suspected);
	}

	@Override
	public boolean suspects(NodeRef at, NodeRef target) {
		return state.at(at).suspected.contains(target);
	}

	@Override
	public Set<NodeRef> suspected(NodeRef at) {
		return Set.copyOf(state.at(at).suspected);
	}

	@Override
	public void onChange(Listener listener) {
		listeners.add(listener);
	}
}
