package zip.hedlund.pureedgesim.edgesdk.detect;

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
 * Heartbeat failure detector: every {@code period} seconds the monitoring node
 * sends a probe to each target and waits {@code timeout} seconds for the answer.
 * After {@code misses} consecutive unanswered probes it suspects the target; one
 * answer restores trust. Every change of belief is declared to the SDK (for the
 * detection metrics) unless disabled.
 *
 * <pre>{@code
 * detector = new HeartbeatDetector(ctx, 1.0, 0.5, 2);
 * detector.monitor(device, servers);
 * if (detector.suspects(device, server)) ...
 * }</pre>
 */
public final class HeartbeatDetector implements FailureDetector {

	private static final class State {
		final Map<NodeRef, Integer> misses = new HashMap<>();
		final Set<NodeRef> suspected = new TreeSet<>();
		final List<Cancellable> timers = new ArrayList<>();
		final Map<NodeRef, TimeoutTracker> rtt = new HashMap<>();
	}

	private final SdkContext ctx;
	private final double period;
	private final double timeout;
	private final int misses;
	private final boolean declare;
	private final NodeLocal<State> state;
	private final List<Listener> listeners = new ArrayList<>();

	/**
	 * @param period  seconds between probes
	 * @param timeout seconds to wait for an answer
	 * @param misses  consecutive misses before suspecting
	 */
	public HeartbeatDetector(SdkContext ctx, double period, double timeout, int misses) {
		this(ctx, period, timeout, misses, true);
	}

	public HeartbeatDetector(SdkContext ctx, double period, double timeout, int misses, boolean declare) {
		if (!(period > 0) || !(timeout > 0) || misses < 1)
			throw new IllegalArgumentException("need period > 0, timeout > 0, misses >= 1");
		this.ctx = ctx;
		this.period = period;
		this.timeout = timeout;
		this.misses = misses;
		this.declare = declare;
		this.state = ctx.nodeLocal(n -> new State());
	}

	@Override
	public void monitor(NodeRef at, Collection<NodeRef> targets) {
		State s = state.at(at);
		s.timers.forEach(Cancellable::cancel);
		s.timers.clear();
		for (NodeRef target : new LinkedHashSet<>(targets)) {
			if (target.equals(at))
				continue;
			s.rtt.put(target, new TimeoutTracker(timeout / 4, timeout));
			// spread the first probes over one period
			double offset = ctx.rng().nextDouble() * period;
			s.timers.add(ctx.schedule(at, offset, () -> {
				probe(at, target);
				s.timers.add(ctx.every(at, period, () -> probe(at, target)));
			}));
		}
	}

	private void probe(NodeRef at, NodeRef target) {
		ctx.request(at, target, PROBE_BYTES, PROBE, timeout, reply -> {
			State s = state.at(at);
			s.misses.put(target, 0);
			s.rtt.computeIfAbsent(target, k -> new TimeoutTracker(timeout / 4, timeout)).observe(reply.rtt());
			if (s.suspected.remove(target))
				changed(at, target, false);
		}, () -> {
			State s = state.at(at);
			int m = s.misses.merge(target, 1, Integer::sum);
			if (m >= misses && s.suspected.add(target))
				changed(at, target, true);
		});
	}

	private void changed(NodeRef at, NodeRef target, boolean suspected) {
		if (declare)
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

	/** @return the smoothed round-trip time to a target, or NaN */
	public double rtt(NodeRef at, NodeRef target) {
		TimeoutTracker t = state.at(at).rtt.get(target);
		return t == null ? Double.NaN : t.smoothedRtt();
	}

	@Override
	public void onChange(Listener listener) {
		listeners.add(listener);
	}
}
