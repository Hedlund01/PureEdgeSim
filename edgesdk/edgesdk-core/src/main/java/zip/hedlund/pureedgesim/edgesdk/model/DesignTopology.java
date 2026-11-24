package zip.hedlund.pureedgesim.edgesdk.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The topology <em>as designed</em>: the nodes, the links that exist, the groups,
 * and the initial positions. It never changes during a run and says nothing about
 * which links are up; it is what an algorithm may know before the system starts.
 * <p>
 * Design latencies are computed over every link, from the channel latency and the
 * initial distance between the endpoints, and only through nodes that relay.
 */
public final class DesignTopology {

	private final Map<String, NodeDecl> nodes = new LinkedHashMap<>();
	private final List<LinkSpec> links;
	private final Map<String, GroupSpec> groups;
	private final Map<String, List<LinkSpec>> linksByNode = new HashMap<>();
	private final Map<String, Map<String, Double>> latencyCache = new HashMap<>();
	private final Map<String, Map<String, String>> previousCache = new HashMap<>();

	public DesignTopology(List<NodeDecl> nodes, List<LinkSpec> links, Map<String, GroupSpec> groups) {
		for (NodeDecl n : nodes)
			this.nodes.put(n.name(), n);
		this.links = List.copyOf(links);
		this.groups = Collections.unmodifiableMap(new LinkedHashMap<>(groups));
		for (LinkSpec l : links) {
			linksByNode.computeIfAbsent(l.a().name(), k -> new ArrayList<>()).add(l);
			linksByNode.computeIfAbsent(l.b().name(), k -> new ArrayList<>()).add(l);
		}
	}

	/** @return every node, in declaration order */
	public List<NodeDecl> nodes() {
		return List.copyOf(nodes.values());
	}

	/** @return the names of every node of a tier */
	public List<NodeRef> nodes(Tier tier) {
		return nodes.values().stream().filter(n -> n.tier() == tier).map(NodeDecl::ref).toList();
	}

	/** @return the names of every edge node, static or mobile */
	public List<NodeRef> edgeNodes() {
		return nodes.values().stream().filter(n -> n.tier().isEdge()).map(NodeDecl::ref).toList();
	}

	public Optional<NodeDecl> find(String name) {
		return Optional.ofNullable(nodes.get(name));
	}

	public NodeDecl node(String name) {
		NodeDecl n = nodes.get(name);
		if (n == null)
			throw new IllegalArgumentException("unknown node \"" + name + "\"");
		return n;
	}

	public NodeDecl node(NodeRef ref) {
		return node(ref.name());
	}

	public boolean contains(String name) {
		return nodes.containsKey(name);
	}

	public List<LinkSpec> links() {
		return links;
	}

	public List<LinkSpec> linksOf(NodeRef node) {
		return List.copyOf(linksByNode.getOrDefault(node.name(), List.of()));
	}

	/** @return the nodes that share a link with this one */
	public Set<NodeRef> neighbours(NodeRef node) {
		Set<NodeRef> out = new TreeSet<>();
		for (LinkSpec l : linksOf(node))
			out.add(l.other(node));
		return out;
	}

	public Map<String, GroupSpec> groups() {
		return groups;
	}

	public GroupSpec group(String name) {
		GroupSpec g = groups.get(name);
		if (g == null)
			throw new IllegalArgumentException("unknown group \"" + name + "\"");
		return g;
	}

	/** @return the names of the groups the node belongs to */
	public Set<String> groupsOf(NodeRef node) {
		return groups.values().stream().filter(g -> g.members().contains(node.name())).map(GroupSpec::name)
				.collect(Collectors.toCollection(TreeSet::new));
	}

	/** Latency of one design link, in seconds, from the channel and initial distance. */
	public double designLatency(LinkSpec link) {
		double km = node(link.a()).position().distanceTo(node(link.b()).position()) / 1000.0;
		return (link.channel().latencyMs() + link.channel().latencyPerKmMs() * km) / 1000.0;
	}

	/**
	 * @return the design latency in seconds of the lowest-latency path from
	 *         {@code from} to {@code to}, or infinity when no designed path exists
	 */
	public synchronized double designLatency(NodeRef from, NodeRef to) {
		return dijkstra(from.name()).getOrDefault(to.name(), Double.POSITIVE_INFINITY);
	}

	/** @return the designed lowest-latency path, both ends included, or empty */
	public synchronized List<NodeRef> designPath(NodeRef from, NodeRef to) {
		Map<String, Double> dist = dijkstra(from.name());
		if (!dist.containsKey(to.name()))
			return List.of();
		Map<String, String> prev = previousCache.get(from.name());
		List<NodeRef> path = new ArrayList<>();
		for (String at = to.name(); at != null; at = prev.get(at))
			path.add(0, NodeRef.of(at));
		return path;
	}

	/** @return the candidates ordered by design latency from {@code from}, unreachable ones left out */
	public List<NodeRef> byDesignLatency(NodeRef from, Collection<NodeRef> candidates) {
		List<NodeRef> out = new ArrayList<>();
		for (NodeRef c : new LinkedHashSet<>(candidates))
			if (c.equals(from) || designLatency(from, c) < Double.POSITIVE_INFINITY)
				out.add(c);
		out.sort(Comparator.comparingDouble((NodeRef c) -> c.equals(from) ? 0 : designLatency(from, c))
				.thenComparing(NodeRef::name));
		return out;
	}

	/** @return the candidate with the lowest design latency from {@code from} */
	public Optional<NodeRef> nearest(NodeRef from, Collection<NodeRef> candidates) {
		List<NodeRef> sorted = byDesignLatency(from, candidates);
		return sorted.isEmpty() ? Optional.empty() : Optional.of(sorted.get(0));
	}

	private Map<String, Double> dijkstra(String source) {
		Map<String, Double> cached = latencyCache.get(source);
		if (cached != null)
			return cached;
		Map<String, Double> dist = new HashMap<>();
		Map<String, String> prev = new HashMap<>();
		PriorityQueue<Map.Entry<String, Double>> queue = new PriorityQueue<>(Map.Entry.comparingByValue());
		dist.put(source, 0.0);
		queue.add(Map.entry(source, 0.0));
		while (!queue.isEmpty()) {
			Map.Entry<String, Double> e = queue.poll();
			String u = e.getKey();
			if (e.getValue() > dist.get(u))
				continue;
			// Only the source and relaying nodes forward traffic.
			if (!u.equals(source) && !node(u).spec().relays())
				continue;
			for (LinkSpec l : linksByNode.getOrDefault(u, List.of())) {
				String v = l.other(NodeRef.of(u)).name();
				double d = dist.get(u) + designLatency(l);
				if (d < dist.getOrDefault(v, Double.POSITIVE_INFINITY)) {
					dist.put(v, d);
					prev.put(v, u);
					queue.add(Map.entry(v, d));
				}
			}
		}
		latencyCache.put(source, dist);
		previousCache.put(source, prev);
		return dist;
	}
}
