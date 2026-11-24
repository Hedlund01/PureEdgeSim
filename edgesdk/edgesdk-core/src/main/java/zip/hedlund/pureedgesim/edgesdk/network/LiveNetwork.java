package zip.hedlund.pureedgesim.edgesdk.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.jgrapht.graph.MaskSubgraph;

import zip.hedlund.pureedgesim.edgesdk.engine.SdkNode;
import zip.hedlund.pureedgesim.edgesdk.model.LinkRef;

/**
 * The network as it is now: the graph of every link, with links that are down
 * and nodes that are down hidden, and only relaying nodes used as intermediate
 * hops. Paths minimise design latency and are cached until the network changes.
 * <p>
 * This is ground truth: the engine routes with it and the metrics judge with it.
 * Algorithms never see it.
 */
public final class LiveNetwork {

	private final DirectedWeightedMultigraph<SdkNode, SdkNetworkLink> graph = new DirectedWeightedMultigraph<>(
			SdkNetworkLink.class);
	private final Map<LinkRef, SdkLink> links = new LinkedHashMap<>();
	private final Map<SdkNode, List<SdkLink>> byNode = new HashMap<>();
	private final Map<SdkNode, Map<SdkNode, Optional<GraphPath<SdkNode, SdkNetworkLink>>>> cache = new HashMap<>();
	private long version;

	public void add(SdkLink link) {
		links.put(link.ref(), link);
		byNode.computeIfAbsent(link.a(), k -> new ArrayList<>()).add(link);
		byNode.computeIfAbsent(link.b(), k -> new ArrayList<>()).add(link);
		for (SdkNetworkLink d : List.of(link.forward(), link.backward())) {
			graph.addVertex(d.from());
			graph.addVertex(d.to());
			graph.addEdge(d.from(), d.to(), d);
			graph.setEdgeWeight(d, Math.max(1e-6, d.getLatency()));
		}
		changed();
	}

	public void addNode(SdkNode node) {
		graph.addVertex(node);
	}

	/** Invalidates cached paths; called on every state change. */
	public void changed() {
		version++;
		cache.clear();
	}

	public long version() {
		return version;
	}

	public List<SdkLink> links() {
		return List.copyOf(links.values());
	}

	public SdkLink link(LinkRef ref) {
		SdkLink l = links.get(ref);
		if (l == null)
			throw new IllegalArgumentException("unknown link " + ref);
		return l;
	}

	public List<SdkLink> linksOf(SdkNode node) {
		return byNode.getOrDefault(node, List.of());
	}

	/** @return the lowest-latency live path, or empty */
	public Optional<GraphPath<SdkNode, SdkNetworkLink>> path(SdkNode from, SdkNode to) {
		if (!from.isUp() || !to.isUp() || from == to)
			return Optional.empty();
		return cache.computeIfAbsent(from, k -> new HashMap<>()).computeIfAbsent(to, k -> compute(from, to));
	}

	private Optional<GraphPath<SdkNode, SdkNetworkLink>> compute(SdkNode from, SdkNode to) {
		MaskSubgraph<SdkNode, SdkNetworkLink> view = new MaskSubgraph<>(graph,
				v -> v != from && v != to && (!v.isUp() || !v.relays()), e -> !e.parent().isUp());
		GraphPath<SdkNode, SdkNetworkLink> p = DijkstraShortestPath.findPathBetween(view, from, to);
		return Optional.ofNullable(p);
	}

	/** @return the first link of the live path, or null */
	public SdkNetworkLink nextHop(SdkNode at, SdkNode dst) {
		return path(at, dst).map(p -> p.getEdgeList().get(0)).orElse(null);
	}

	/** @return true when {@code b} can be reached from {@code a} now */
	public boolean reachable(SdkNode a, SdkNode b) {
		if (a == b)
			return a.isUp();
		return path(a, b).isPresent();
	}

	public int linksDown() {
		return (int) links.values().stream().filter(l -> !l.isUp()).count();
	}
}
