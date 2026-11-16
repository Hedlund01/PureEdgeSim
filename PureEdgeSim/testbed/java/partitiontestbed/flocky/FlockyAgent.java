package partitiontestbed.flocky;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

import partitiontestbed.EdgeServer;
import partitiontestbed.Trait;
import partitiontestbed.flocky.Oam.Definition;
import partitiontestbed.flocky.Oam.NodeSummary;

/**
 * The Flocky services of one edge node: discovery, metadata repository and
 * deployment.
 * <p>
 * <b>Discovery</b> is Algorithm 1 of the paper. Every round the node pings the
 * nodes it knows and those that contacted it, keeps the ones within the maximum
 * discovery distance, and explores the lists of known nodes of those it keeps.
 * The distance is the round trip time of the ping, measured on the live topology,
 * and the range widens where the node knows few others
 * ({@link #adjustedDistance(int)}). A node that does not answer a ping is not in
 * range, whatever its distance was before.
 * <p>
 * The algorithm alone never relearns a node it has dropped unless someone in
 * range still knows it, so two halves of a healed partition would stay apart for
 * ever. Two details, which the paper leaves open, keep the run from hiding the
 * healing: a node that stopped answering is kept aside and pinged again every
 * round until {@code discovery.forget_after_s} has passed, and the bootstrap
 * peers of a node are pinged whenever they are not known.
 * <p>
 * <b>The repository</b> pulls a {@link NodeSummary} from every known node it can
 * reach, every repository round, and learns the component definitions the others
 * know. A summary is never refreshed from a node that cannot be reached, so the
 * orchestrator reasons on what the node looked like before the partition: that
 * staleness is one of the things the example measures.
 * <p>
 * <b>The deployment service</b> is the truth: the instances that run on this
 * node, their clients, and the memory they hold. It refuses what it cannot run,
 * whatever the requester believed.
 *
 * @see FlockyStrategy
 */
public class FlockyAgent {

	private final FlockyStrategy flocky;
	private final FlockyConfig config;
	/** The edge server this agent runs on. */
	public final EdgeServer server;
	/** Its edge data center. */
	public final ComputingNode node;
	/** Traits it advertises. */
	public final Set<Trait> traits;

	// Discovery
	/** Nodes in range at the last round, in the order they were found. */
	private final Set<FlockyAgent> known = new LinkedHashSet<>();
	/** Round trip time measured at the last ping, per known node, in seconds. */
	private final Map<FlockyAgent, Double> distance = new HashMap<>();
	/** Nodes that pinged this one since the last round: N_new of the paper. */
	private final Set<FlockyAgent> heardFrom = new LinkedHashSet<>();
	/** Nodes that stopped answering, with the time they did. */
	private final Map<FlockyAgent, Double> lost = new LinkedHashMap<>();
	/** Nodes this one was configured with. */
	private final List<FlockyAgent> seeds = new ArrayList<>();

	// Repository
	/** Last summary pulled from each known node. */
	private final Map<FlockyAgent, NodeSummary> summaries = new HashMap<>();
	/** Component definitions this node knows, by identifier. */
	private final Map<Integer, Definition> definitions = new TreeMap<>();

	// Deployment service
	/** Memory this node lends to deployments, in megabytes. */
	private final double memoryCapacity;
	/** Memory held by the instances that run, in megabytes. */
	private double memoryUsed;
	/** Clients of each instance that runs on this node, by definition. */
	private final Map<Definition, Set<Integer>> instances = new LinkedHashMap<>();

	/** Control plane bytes this node sent and received. */
	private long controlBytes;

	FlockyAgent(FlockyStrategy flocky, EdgeServer server) {
		this.flocky = flocky;
		this.config = flocky.config;
		this.server = server;
		this.node = server.node;
		this.traits = server.traits;
		this.memoryCapacity = node.getRamCapacity() * config.memoryShare;
	}

	/** @return the name of the edge data center */
	public String name() {
		return node.getName();
	}

	@Override
	public String toString() {
		return name();
	}

	// ------------------------------------------------------------ discovery

	/** Adds a node this one starts from. */
	void addSeed(FlockyAgent seed) {
		seeds.add(seed);
		heardFrom.add(seed);
	}

	/**
	 * Runs one discovery round: Algorithm 1 of the paper, on the live topology.
	 */
	void discover() {
		double now = flocky.world.now();
		lost.values().removeIf(since -> now - since > config.forgetAfter);

		Deque<FlockyAgent> check = new ArrayDeque<>();
		Set<FlockyAgent> queued = new HashSet<>();
		queue(check, queued, known);
		queue(check, queued, heardFrom);
		queue(check, queued, lost.keySet());
		for (FlockyAgent seed : seeds)
			if (!known.contains(seed))
				queue(check, queued, List.of(seed));
		heardFrom.clear();

		Map<FlockyAgent, Double> measured = new HashMap<>();
		double dMin = Double.POSITIVE_INFINITY;
		if (!check.isEmpty()) {
			FlockyAgent first = check.peek();
			dMin = ping(first);
			measured.put(first, dMin);
		}
		boolean inReach = false;
		while (!check.isEmpty()) {
			FlockyAgent candidate = check.poll();
			Double previous = measured.get(candidate);
			double d = previous != null ? previous : ping(candidate);
			measured.put(candidate, d);
			if (d == Double.POSITIVE_INFINITY) {
				// No answer: the node is not in range, and it is retried until it is
				// forgotten.
				if (known.remove(candidate))
					lost.putIfAbsent(candidate, now);
				distance.remove(candidate);
				summaries.remove(candidate);
				continue;
			}
			lost.remove(candidate);
			double dMax = adjustedDistance(known.size());
			if (d <= dMax) {
				known.add(candidate);
				distance.put(candidate, d);
				queue(check, queued, getKnownNodes(candidate));
				inReach = true;
				dMin = Math.min(dMin, d);
			} else if (dMin >= dMax) {
				// Still outside its own neighbourhood: explore through the closest node
				// found so far, and keep it as an anchor for the next round.
				queue(check, queued, getKnownNodes(candidate));
				if (known.size() <= 1 || d <= dMin) {
					dMin = d;
					known.add(candidate);
					distance.put(candidate, d);
				} else
					forget(candidate);
			}
		}
		if (inReach) {
			double limit = adjustedDistance(known.size());
			for (FlockyAgent agent : new ArrayList<>(known))
				if (measured.getOrDefault(agent, Double.POSITIVE_INFINITY) > limit)
					forget(agent);
		}
	}

	/**
	 * The maximum discovery distance, widened where the node knows few others: the
	 * {@code AdjustedDistance} of the paper.
	 *
	 * @param knownCount the number of nodes known
	 * @return the distance within which a node is kept, in seconds of round trip
	 */
	double adjustedDistance(int knownCount) {
		double localDensity = config.assumedDensity / Math.max(1, knownCount);
		return config.maxDiscoveryDistance * Math.max(1, Math.sqrt(localDensity));
	}

	/**
	 * Pings another node.
	 *
	 * @param target the node to ping
	 * @return the round trip time, in seconds, or infinity when it does not answer
	 */
	private double ping(FlockyAgent target) {
		double latency = flocky.world.topology().latency(node, target.node);
		if (latency == Double.POSITIVE_INFINITY) {
			controlBytes += config.pingBytes;
			return latency;
		}
		exchange(target, 2L * config.pingBytes);
		if (!target.known.contains(this))
			target.heardFrom.add(this);
		return 2 * latency;
	}

	/**
	 * Asks a node for its list of known nodes.
	 *
	 * @param target the node to ask
	 * @return its list, or an empty list when it cannot be reached
	 */
	private List<FlockyAgent> getKnownNodes(FlockyAgent target) {
		if (!flocky.world.topology().reachable(node, target.node))
			return List.of();
		exchange(target, config.pingBytes + (long) config.nodeEntryBytes * target.known.size());
		return new ArrayList<>(target.known);
	}

	private void queue(Deque<FlockyAgent> check, Set<FlockyAgent> queued,
			Iterable<FlockyAgent> agents) {
		for (FlockyAgent agent : agents)
			if (agent != this && queued.add(agent))
				check.add(agent);
	}

	private void forget(FlockyAgent agent) {
		known.remove(agent);
		distance.remove(agent);
		summaries.remove(agent);
	}

	/** @return the nodes in range at the last round */
	public Set<FlockyAgent> known() {
		return Collections.unmodifiableSet(known);
	}

	/**
	 * @param agent a known node
	 * @return the round trip time measured at the last round, in seconds
	 */
	public double distanceTo(FlockyAgent agent) {
		return agent == this ? 0 : distance.getOrDefault(agent, Double.POSITIVE_INFINITY);
	}

	// ----------------------------------------------------------- repository

	/**
	 * Runs one repository round: pulls a summary from every known node it can
	 * reach, and the definitions it does not know yet.
	 */
	void syncMetadata() {
		for (FlockyAgent agent : known) {
			if (!flocky.world.topology().reachable(node, agent.node))
				continue;
			exchange(agent, config.pingBytes + agent.summaryBytes());
			summaries.put(agent, agent.summary());
			for (Definition definition : agent.definitions.values())
				if (definitions.putIfAbsent(definition.id, definition) == null)
					exchange(agent, config.manifestBytes);
		}
		summaries.keySet().retainAll(known);
	}

	/** Makes a definition known to this node, as an operator would introduce it. */
	void learn(Definition definition) {
		definitions.put(definition.id, definition);
	}

	/** @return the definitions this node knows */
	public Iterable<Definition> definitions() {
		return definitions.values();
	}

	/** @return whether this node knows the definition */
	public boolean knows(Definition definition) {
		return definitions.containsKey(definition.id);
	}

	/**
	 * @param agent a known node
	 * @return its last summary, or {@code null} when none was pulled yet; the
	 *         summary of this node is always fresh
	 */
	public NodeSummary summaryOf(FlockyAgent agent) {
		return agent == this ? summary() : summaries.get(agent);
	}

	/** @return a summary of this node as it is now */
	NodeSummary summary() {
		Set<Integer> open = new HashSet<>();
		for (Map.Entry<Definition, Set<Integer>> instance : instances.entrySet())
			if (instance.getValue().size() < config.maxClientsPerInstance)
				open.add(instance.getKey().id);
		return new NodeSummary(traits, memoryCapacity, freeMemory(), open, flocky.world.now());
	}

	private long summaryBytes() {
		return config.summaryBaseBytes
				+ (long) config.definitionIdBytes * (definitions.size() + instances.size());
	}

	// ----------------------------------------------------------- deployment

	/**
	 * The deployment API: runs the definition for one more client, on an instance
	 * that already runs it when one has room, or on a new one.
	 *
	 * @param definition the definition to run
	 * @param client     the device that will call it
	 * @return whether the node took it
	 */
	boolean deploy(Definition definition, int client) {
		if (!definition.fits(traits))
			return false;
		Set<Integer> clients = instances.get(definition);
		if (clients != null && clients.contains(client))
			return true;
		if (clients != null)
			return clients.size() < config.maxClientsPerInstance && clients.add(client);
		if (freeMemory() < definition.memory)
			return false;
		clients = new HashSet<>();
		clients.add(client);
		instances.put(definition, clients);
		memoryUsed += definition.memory;
		return true;
	}

	/**
	 * Removes a client, and the instance with its last client.
	 *
	 * @param definition the definition it called
	 * @param client     the device
	 */
	void release(Definition definition, int client) {
		Set<Integer> clients = instances.get(definition);
		if (clients == null || !clients.remove(client) || !clients.isEmpty())
			return;
		instances.remove(definition);
		memoryUsed -= definition.memory;
	}

	/** @return whether an instance of the definition runs here with room for one more client */
	boolean hasOpenInstance(Definition definition) {
		Set<Integer> clients = instances.get(definition);
		return clients != null && clients.size() < config.maxClientsPerInstance;
	}

	/** @return the memory still free for deployments, in megabytes */
	public double freeMemory() {
		return memoryCapacity - memoryUsed;
	}

	/** @return the memory this node lends to deployments, in megabytes */
	public double memoryCapacity() {
		return memoryCapacity;
	}

	/** @return the number of instances running on this node */
	public int instanceCount() {
		return instances.size();
	}

	// -------------------------------------------------------------- traffic

	/** Counts a message exchange with another node, on both ends. */
	void exchange(FlockyAgent other, long bytes) {
		controlBytes += bytes;
		other.controlBytes += bytes;
	}

	/** @return the control plane bytes this node sent and received so far */
	public long controlBytes() {
		return controlBytes;
	}
}
