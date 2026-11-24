package zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import zip.hedlund.pureedgesim.edgesdk.algorithm.NodeLocal;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Response;
import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * Flocky's Discovery service (Algorithm 1 of Goethals et al., "Flocky:
 * Decentralized Intent-Based Edge Orchestration Using Open Application Model",
 * IEEE TSC 2026, after SoSwirly). Every {@code period} seconds a node pings the
 * nodes it knows and those that contacted it since the last round, depth first:
 * a node within range is kept and asked for the nodes it knows; a node out of
 * range is ignored, but its known nodes are still explored while nothing in
 * range has been found, so a node placed far from its neighbourhood can walk
 * back to it. The range is {@code maxRtt}, widened by
 * {@code sqrt(assumedDensity / known)} while the node knows fewer nodes than
 * the density it assumes.
 * <p>
 * Distances are the round-trip times of the pings. Two lines of the published
 * pseudocode are read as set differences ({@code N_known \ N_remove}, and
 * {@code N_new \ N_ignore \ N_known} in MergeNodes), and an out-of-range node
 * that is the nearest seen so far is kept as a stepping stone until a round
 * finds a node in range.
 */
public final class FlockyDiscovery {

	record Ping() {
	}

	record GetKnownNodes() {
	}

	static final Ping PING = new Ping();
	static final GetKnownNodes GET_KNOWN = new GetKnownNodes();
	static final long PING_BYTES = 32;

	private static final class State {
		final Set<NodeRef> known = new TreeSet<>();
		final Set<NodeRef> fresh = new TreeSet<>();
		final Map<NodeRef, Double> rtt = new HashMap<>();
		boolean running;
	}

	private final SdkContext ctx;
	private final double maxRtt;
	private final double assumedDensity;
	private final double pingTimeout;
	private final double period;
	private final NodeLocal<State> state;

	/**
	 * @param maxRtt         discovery range, seconds of round-trip time
	 * @param assumedDensity number of nodes a node expects within range
	 * @param pingTimeout    seconds to wait for a ping or a node list
	 * @param period         seconds between discovery rounds
	 */
	public FlockyDiscovery(SdkContext ctx, double maxRtt, double assumedDensity, double pingTimeout, double period) {
		if (!(maxRtt > 0) || !(assumedDensity > 0) || !(pingTimeout > 0) || !(period > 0))
			throw new IllegalArgumentException("discovery parameters must be positive");
		this.ctx = ctx;
		this.maxRtt = maxRtt;
		this.assumedDensity = assumedDensity;
		this.pingTimeout = pingTimeout;
		this.period = period;
		this.state = ctx.nodeLocal(n -> new State());
	}

	/**
	 * Starts discovery at a node. Call it again after the node restarts.
	 *
	 * @param bootstrap the nodes known when it boots; at least one is needed
	 */
	public void start(NodeRef at, Collection<NodeRef> bootstrap) {
		State s = state.at(at);
		for (NodeRef n : bootstrap)
			if (!n.equals(at))
				s.known.add(n);
		ctx.schedule(at, ctx.rng().nextDouble() * period, () -> {
			round(at);
			ctx.every(at, period, () -> round(at));
		});
	}

	/** Runs a discovery round now, unless one is running. */
	public void round(NodeRef at) {
		State s = state.at(at);
		if (s.running)
			return;
		s.running = true;
		new Round(at, s).next();
	}

	/** @return the nodes {@code at} currently knows */
	public Set<NodeRef> known(NodeRef at) {
		return Set.copyOf(state.at(at).known);
	}

	/** @return the last round-trip time {@code at} measured to {@code to}, or infinity */
	public double rtt(NodeRef at, NodeRef to) {
		return state.at(at).rtt.getOrDefault(to, Double.POSITIVE_INFINITY);
	}

	/** @return the current range of {@code at}, seconds */
	public double range(NodeRef at) {
		return adjustedDistance(state.at(at).known.size());
	}

	/** AdjustedDistance of Algorithm 1. */
	double adjustedDistance(int known) {
		return maxRtt * Math.max(1, Math.sqrt(assumedDensity / Math.max(1, known)));
	}

	/**
	 * Answers the Discovery API at {@code at}.
	 *
	 * @return the reply, or {@code null} when the request is not a discovery request
	 */
	Object handle(NodeRef at, NodeRef from, Object request) {
		if (!(request instanceof Ping) && !(request instanceof GetKnownNodes))
			return null;
		State s = state.at(at);
		if (!s.known.contains(from))
			s.fresh.add(from);
		if (request instanceof Ping)
			return Boolean.TRUE;
		List<NodeRef> known = List.copyOf(s.known);
		return Response.of(known, 32 + 16L * known.size());
	}

	private final class Round {

		final NodeRef at;
		final State s;
		final Deque<NodeRef> check = new ArrayDeque<>();
		final Set<NodeRef> ignore = new HashSet<>();
		double dmin = Double.POSITIVE_INFINITY;
		boolean inReach;

		Round(NodeRef at, State s) {
			this.at = at;
			this.s = s;
			Set<NodeRef> start = new TreeSet<>(s.known);
			start.addAll(s.fresh);
			s.fresh.clear();
			check.addAll(start);
		}

		void next() {
			if (state.at(at) != s)
				return;
			if (check.isEmpty()) {
				finish();
				return;
			}
			NodeRef nc = check.pollFirst();
			ctx.request(at, nc, PING_BYTES, PING, pingTimeout, reply -> visited(nc, reply.rtt()),
					() -> visited(nc, Double.POSITIVE_INFINITY));
		}

		void visited(NodeRef nc, double d) {
			s.rtt.put(nc, d);
			double dmax = adjustedDistance(s.known.size());
			if (d <= dmax) {
				s.known.add(nc);
				inReach = true;
				dmin = Math.min(dmin, d);
				explore(nc);
				return;
			}
			ignore.add(nc);
			boolean explore = dmin >= dmax && d < Double.POSITIVE_INFINITY;
			if (d < Double.POSITIVE_INFINITY && (s.known.size() <= 1 || d <= dmin)) {
				dmin = d;
				s.known.add(nc);
			} else if (s.known.size() > 1)
				// a silent node is kept only when it is the last one known
				s.known.remove(nc);
			if (explore)
				explore(nc);
			else
				next();
		}

		void explore(NodeRef nc) {
			ctx.request(at, nc, PING_BYTES, GET_KNOWN, pingTimeout, reply -> {
				@SuppressWarnings("unchecked")
				List<NodeRef> found = (List<NodeRef>) reply.payload();
				// depth first: the nodes found are checked next
				for (int i = found.size() - 1; i >= 0; i--) {
					NodeRef n = found.get(i);
					if (!n.equals(at) && !ignore.contains(n) && !s.known.contains(n) && !check.contains(n))
						check.addFirst(n);
				}
				next();
			}, this::next);
		}

		void finish() {
			if (inReach) {
				double dmax = adjustedDistance(s.known.size());
				s.known.removeIf(n -> s.rtt.getOrDefault(n, Double.POSITIVE_INFINITY) > dmax);
			}
			s.running = false;
			ctx.metrics().add("flocky.discovery_rounds", 1);
			ctx.metrics().record("flocky.known_nodes", s.known.size());
		}
	}
}
