package partitiontestbed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm.SingleSourcePaths;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.MaskSubgraph;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.network.InfrastructureGraph;
import com.mechalikh.pureedgesim.network.NetworkLink;

import partitiontestbed.NetworkPolicy.Layer;

/**
 * The infrastructure graph as it is right now, with the links that are down
 * masked out.
 * <p>
 * The framework computes the shortest paths once, when the topology is built,
 * and routes over them for the whole run: a path never breaks. This class keeps
 * the graph untouched and routes over a view of it that hides every link that is
 * currently cut, so a partition changes the routes the moment it starts and
 * gives them back the moment it heals.
 * <p>
 * A link is out of the routes for one of three reasons:
 * <ul>
 * <li>it is <b>cut</b>, by a partition or a random failure. A link can be cut for
 * several reasons at once, so each cut is counted and the link comes back only
 * when the last reason is gone;</li>
 * <li>one of its ends is a <b>failed server</b>, which takes every link it has,
 * including those created while it is down;</li>
 * <li>it is <b>disabled</b>: a radio link that is not in use or out of range. A
 * device keeps one radio link enabled, to the access server it is attached to,
 * and a mesh link between two servers of which one moves is enabled while they
 * are in range. A disabled link is not a failure, and is not counted as a link
 * down.</li>
 * </ul>
 * The time each link last left the routes is kept, which is how a transfer that
 * was crossing it is found and failed.
 * <p>
 * What is routed over the links that carry is the strategy's
 * {@link NetworkPolicy}: a path uses only links between layers the policy uses,
 * and goes through a node, rather than starting or ending at it, only when that
 * node relays. With {@link NetworkPolicy#hierarchical()}, the default, the cloud
 * and the devices never relay: two edge servers whose groups both reached the
 * cloud do not talk through it during a partition, as a partition means, and a
 * device is an end of a path only. A {@linkplain #setBarrier barrier} also keeps
 * out of the routes the links that join two groups of a partition without being
 * cut by it, such as a radio contact the scenario or the policy does not let
 * bridge them.
 * <p>
 * @see TestbedNetworkModel
 * @see FailureInjector
 */
public class LiveTopology {

	/** The full graph built by the topology creator. */
	private final Graph<ComputingNode, NetworkLink> graph;
	/** What the strategy routes over. */
	private final NetworkPolicy policy;
	/** The layer of each node. */
	private final Function<ComputingNode, Layer> layerOf;
	/** Relaying set on a node by the strategy, over the default of its layer. */
	private final Map<ComputingNode, Boolean> relayOverride = new HashMap<>();
	/** Links that join two groups of a partition and must not carry the strategy's traffic. */
	private Predicate<NetworkLink> barrier = link -> false;
	/** Number of reasons each cut link is down for. */
	private final Map<NetworkLink, Integer> cuts = new HashMap<>();
	/** Number of reasons each failed node is down for. */
	private final Map<ComputingNode, Integer> failed = new HashMap<>();
	/** Radio links not in use or out of range. */
	private final Set<NetworkLink> disabled = new HashSet<>();
	/** Last time each link went down or was disabled. */
	private final Map<NetworkLink, Double> lastCut = new HashMap<>();
	/** Last time each node failed. */
	private final Map<ComputingNode, Double> lastFailure = new HashMap<>();
	/** Shortest paths from each source, until the next change. */
	private final Map<ComputingNode, SingleSourcePaths<ComputingNode, NetworkLink>> cache = new HashMap<>();
	/** Called whenever a link leaves the routes. */
	private final List<Runnable> lossListeners = new ArrayList<>();

	/**
	 * A topology in which every node but the cloud is an edge server, routed with
	 * {@link NetworkPolicy#hierarchical()}.
	 *
	 * @param topology the complete topology of the run
	 * @param cloud    the cloud data center
	 */
	public LiveTopology(InfrastructureGraph topology, ComputingNode cloud) {
		this(topology, NetworkPolicy.hierarchical(), node -> node == cloud ? Layer.CLOUD : Layer.EDGE);
	}

	/**
	 * @param topology the complete topology of the run
	 * @param policy   what the strategy routes over
	 * @param layerOf  the layer of each node
	 */
	public LiveTopology(InfrastructureGraph topology, NetworkPolicy policy, Function<ComputingNode, Layer> layerOf) {
		this.graph = topology.getGraph();
		this.policy = policy;
		this.layerOf = layerOf;
	}

	/**
	 * @param barrier says which links join two groups of a partition in progress
	 *                and must carry nothing of the strategy's, though they are up.
	 *                It is asked again after every change of the topology or of the
	 *                groups ({@link #groupsChanged()}).
	 */
	public void setBarrier(Predicate<NetworkLink> barrier) {
		this.barrier = barrier;
		changed(true);
	}

	/** Forgets the routes after a partition started, ended or a node changed group. */
	public void groupsChanged() {
		changed(true);
	}

	/**
	 * Makes a node relay, or stop relaying, whatever its layer does.
	 *
	 * @param node     the node
	 * @param relaying whether paths may go through it
	 */
	public void setRelay(ComputingNode node, boolean relaying) {
		Boolean before = relayOverride.put(node, relaying);
		if (before == null || before != relaying)
			changed(false);
	}

	/**
	 * @param node a node
	 * @return whether paths may go through it
	 */
	public boolean relays(ComputingNode node) {
		Boolean override = relayOverride.get(node);
		return override != null ? override : policy.relays(layerOf.apply(node));
	}

	/** @return the policy the routes follow */
	public NetworkPolicy policy() {
		return policy;
	}

	/**
	 * @param node a node
	 * @return its layer
	 */
	public Layer layerOf(ComputingNode node) {
		return layerOf.apply(node);
	}

	/**
	 * Takes links down for one more reason.
	 *
	 * @param links the links to cut
	 * @param time  the current simulation time
	 */
	public void cut(Collection<NetworkLink> links, double time) {
		for (NetworkLink link : links) {
			cuts.merge(link, 1, Integer::sum);
			lastCut.put(link, time);
		}
		changed(true);
	}

	/**
	 * Removes one reason for links to be down, and brings back those that have no
	 * reason left.
	 *
	 * @param links the links to restore
	 */
	public void restore(Collection<NetworkLink> links) {
		for (NetworkLink link : links)
			cuts.computeIfPresent(link, (key, count) -> count > 1 ? count - 1 : null);
		changed(false);
	}

	/**
	 * Takes a node down with every link it has or gets, for one more reason.
	 *
	 * @param node the node that fails
	 * @param time the current simulation time
	 */
	public void fail(ComputingNode node, double time) {
		failed.merge(node, 1, Integer::sum);
		lastFailure.put(node, time);
		changed(true);
	}

	/**
	 * Removes one reason for a node to be down.
	 *
	 * @param node the node that is repaired
	 */
	public void repair(ComputingNode node) {
		failed.computeIfPresent(node, (key, count) -> count > 1 ? count - 1 : null);
		changed(false);
	}

	/**
	 * Takes a radio link in or out of use. A disabled link carries nothing, but is
	 * not a link down.
	 *
	 * @param link    the link
	 * @param enabled whether it is in use
	 * @param time    the current simulation time
	 */
	public void enable(NetworkLink link, boolean enabled, double time) {
		if (enabled ? !disabled.remove(link) : !disabled.add(link))
			return;
		if (!enabled)
			lastCut.put(link, time);
		changed(!enabled);
	}

	/**
	 * Changes the latency of a link, which is also its weight in the routes.
	 *
	 * @param link    the link
	 * @param latency its new latency, in seconds
	 */
	public void setLatency(NetworkLink link, double latency) {
		link.setLatency(latency);
		graph.setEdgeWeight(link, latency);
		changed(false);
	}

	/** Forgets the routes, after a link was added to the graph. */
	public void linkAdded() {
		changed(false);
	}

	/**
	 * @param listener called whenever a link leaves the routes
	 */
	public void onLoss(Runnable listener) {
		lossListeners.add(listener);
	}

	private void changed(boolean loss) {
		cache.clear();
		if (loss)
			for (Runnable listener : lossListeners)
				listener.run();
	}

	/**
	 * @param link a link of the topology
	 * @return whether it is delivering: neither cut nor at a failed node. A
	 *         disabled radio link is up, though it carries nothing.
	 */
	public boolean isUp(NetworkLink link) {
		return !cuts.containsKey(link) && !failed.containsKey(graph.getEdgeSource(link))
				&& !failed.containsKey(graph.getEdgeTarget(link));
	}

	/**
	 * @param link a link of the topology
	 * @return whether a transfer can cross it now: it is up and in use
	 */
	public boolean carries(NetworkLink link) {
		return !disabled.contains(link) && isUp(link);
	}

	/**
	 * @param link a link of the topology
	 * @return whether the strategy's traffic may cross it now: it carries, the
	 *         policy uses links between the layers of its ends, and it does not
	 *         join two groups that a partition keeps apart
	 */
	public boolean routable(NetworkLink link) {
		return carries(link) && policy.uses(layerOf.apply(graph.getEdgeSource(link)),
				layerOf.apply(graph.getEdgeTarget(link))) && !barrier.test(link);
	}

	/**
	 * @param node a node of the topology
	 * @return whether it has failed
	 */
	public boolean isFailed(ComputingNode node) {
		return failed.containsKey(node);
	}

	/**
	 * @return the number of directed links currently down, not counting the radio
	 *         links that are only out of use or out of range
	 */
	public int linksDown() {
		Set<NetworkLink> down = new HashSet<>(cuts.keySet());
		for (ComputingNode node : failed.keySet())
			down.addAll(graph.edgesOf(node));
		down.removeAll(disabled);
		return down.size();
	}

	/**
	 * @param from the source node
	 * @param to   the destination node
	 * @return the shortest live path, or {@code null} when the two nodes are cut
	 *         from each other
	 */
	public GraphPath<ComputingNode, NetworkLink> path(ComputingNode from, ComputingNode to) {
		if (!graph.containsVertex(from) || !graph.containsVertex(to))
			return null;
		return paths(from, to).getPath(to);
	}

	/**
	 * @param from the source node
	 * @param to   the destination node
	 * @return the one way propagation delay of the shortest live path, in seconds,
	 *         or {@link Double#POSITIVE_INFINITY} when there is none
	 */
	public double latency(ComputingNode from, ComputingNode to) {
		if (from == to)
			return 0;
		if (!graph.containsVertex(from) || !graph.containsVertex(to))
			return Double.POSITIVE_INFINITY;
		return paths(from, to).getWeight(to);
	}

	/**
	 * @param from the source node
	 * @param to   the destination node
	 * @return whether a message can currently go from one to the other
	 */
	public boolean reachable(ComputingNode from, ComputingNode to) {
		return latency(from, to) < Double.POSITIVE_INFINITY;
	}

	/**
	 * @param links the links a transfer still has to cross
	 * @param since the time at which it was sent
	 * @return whether one of them is out of the routes, or stopped at any time
	 *         since
	 */
	public boolean brokenSince(List<NetworkLink> links, double since) {
		for (NetworkLink link : links) {
			if (!routable(link))
				return true;
			Double cut = lastCut.get(link);
			if (cut != null && cut >= since)
				return true;
			Double source = lastFailure.get(graph.getEdgeSource(link));
			Double target = lastFailure.get(graph.getEdgeTarget(link));
			if (source != null && source >= since || target != null && target >= since)
				return true;
		}
		return false;
	}

	/**
	 * @param node a node
	 * @return the nodes it has a routable link to
	 */
	public List<ComputingNode> neighbours(ComputingNode node) {
		List<ComputingNode> neighbours = new ArrayList<>();
		if (!graph.containsVertex(node))
			return neighbours;
		for (NetworkLink link : graph.outgoingEdgesOf(node))
			if (routable(link) && !neighbours.contains(graph.getEdgeTarget(link)))
				neighbours.add(graph.getEdgeTarget(link));
		return neighbours;
	}

	/**
	 * @param a one node
	 * @param b another node
	 * @return the links between them, in both directions
	 */
	public List<NetworkLink> linksBetween(ComputingNode a, ComputingNode b) {
		List<NetworkLink> links = new ArrayList<>(graph.getAllEdges(a, b));
		links.addAll(graph.getAllEdges(b, a));
		return links;
	}

	/**
	 * The shortest paths from a source over the links it may route on, leaving a
	 * node that does not relay only when it is the source: a path can end at such
	 * a node but not go through it.
	 */
	private SingleSourcePaths<ComputingNode, NetworkLink> paths(ComputingNode from, ComputingNode to) {
		return cache.computeIfAbsent(from, source -> {
			Map<ComputingNode, Boolean> relaying = new HashMap<>();
			Graph<ComputingNode, NetworkLink> view = new MaskSubgraph<>(graph, vertex -> false, link -> {
				ComputingNode hop = graph.getEdgeSource(link);
				return !routable(link) || hop != source && !relaying.computeIfAbsent(hop, this::relays);
			});
			return new DijkstraShortestPath<>(view).getPaths(source);
		});
	}
}
