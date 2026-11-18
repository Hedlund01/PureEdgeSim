package partitiontestbed.flocky;

import java.util.ArrayList;
import java.util.Collections;
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
import partitiontestbed.Service;
import partitiontestbed.StrategyContext;
import partitiontestbed.Trait;
import partitiontestbed.flocky.Oam.Definition;
import partitiontestbed.flocky.Oam.NodeSummary;

/**
 * The Flocky services of one edge node: discovery, metadata repository and
 * deployment, talking to the other nodes only through messages on the simulated
 * network.
 * <p>
 * <b>Discovery</b> is Algorithm 1 of the paper, run as RPCs. Every round the node
 * pings the nodes it knows and those that pinged it, at once; the round trip time
 * of a reply is the distance, and a node that does not answer within
 * {@code discovery.rpc_timeout_ms} is out of range. It keeps the nodes within the
 * maximum discovery distance and asks them for their lists of known nodes, whose
 * entries it pings in turn; the range widens where the node knows few others
 * ({@link #adjustedDistance(int)}). The round ends when every call has answered or
 * timed out. Replies come in the order of their round trip, so the closest nodes
 * are handled first.
 * <p>
 * The algorithm alone never relearns a node it has dropped unless someone in
 * range still knows it, so two halves of a healed partition would stay apart for
 * ever. Two details, which the paper leaves open, keep the run from hiding the
 * healing: a node that stopped answering is kept aside and pinged again every
 * round until {@code discovery.forget_after_s} has passed, and the bootstrap
 * peers of a node are pinged whenever they are not known.
 * <p>
 * <b>The repository</b> asks every known node for its {@link NodeSummary} every
 * repository round, and for the manifests of the definitions it does not know
 * yet. A summary is only as fresh as the last reply, so the orchestrator reasons
 * on what a node looked like before a partition.
 * <p>
 * <b>The deployment service</b> is the truth: the instances that run on this
 * node, their clients, and the memory they hold. It refuses what it cannot run,
 * whatever the requester believed, and frees a client whose access server has
 * not renewed its lease, nor sent it a task, for {@code deployment.orphan_timeout_s}.
 *
 * @see FlockyStrategy
 */
public class FlockyAgent {

	// Requests and replies of the protocol
	/** A discovery ping. */
	static final class Ping {
		final FlockyAgent from;

		Ping(FlockyAgent from) {
			this.from = from;
		}
	}

	/** Asks a node for its list of known nodes. */
	static final class GetKnown {
	}

	/** Asks a node for its summary and the identifiers of its definitions. */
	static final class GetSummary {
	}

	/** The reply to {@link GetSummary}. */
	static final class SummaryReply {
		final NodeSummary summary;
		final List<Definition> definitions;

		SummaryReply(NodeSummary summary, List<Definition> definitions) {
			this.summary = summary;
			this.definitions = definitions;
		}
	}

	/** Asks a node for the manifest of a definition. */
	static final class GetManifest {
		final Definition definition;

		GetManifest(Definition definition) {
			this.definition = definition;
		}
	}

	/** The deployment API: run a definition for one more client. */
	static final class Deploy {
		final Definition definition;
		final int client;

		Deploy(Definition definition, int client) {
			this.definition = definition;
			this.client = client;
		}
	}

	/** Drops a client of an instance. */
	static final class Release {
		final Definition definition;
		final int client;

		Release(Definition definition, int client) {
			this.definition = definition;
			this.client = client;
		}
	}

	/** One discovery round in progress. */
	private final class Round {
		final Map<FlockyAgent, Double> measured = new HashMap<>();
		final Set<FlockyAgent> queued = new HashSet<>();
		int pending;
		double dMin = Double.POSITIVE_INFINITY;
		boolean inReach;
	}

	private final FlockyStrategy flocky;
	private final FlockyConfig config;
	private final StrategyContext ctx;
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
	private Round round;
	/** Whether the last ping of the cloud was answered. */
	boolean cloudReachable = true;

	// Repository
	/** Last summary received from each known node. */
	private final Map<FlockyAgent, NodeSummary> summaries = new HashMap<>();
	/** Component definitions this node knows, by identifier. */
	private final Map<Integer, Definition> definitions = new TreeMap<>();

	// Deployment service
	/** Memory this node lends to deployments, in megabytes. */
	private final double memoryCapacity;
	/** Memory held by the instances that run, in megabytes. */
	private double memoryUsed;
	/** Clients of each instance that runs on this node, by definition, with when each was last served. */
	private final Map<Definition, Map<Integer, Double>> instances = new LinkedHashMap<>();
	/** Clients released after their lease ran out. */
	int orphansReleased;

	FlockyAgent(FlockyStrategy flocky, StrategyContext ctx, EdgeServer server) {
		this.flocky = flocky;
		this.config = flocky.config;
		this.ctx = ctx;
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
	 * Starts a discovery round, unless the last one is still waiting for replies.
	 */
	void discover() {
		if (round != null || !ctx.isUp(node))
			return;
		double now = ctx.now();
		lost.values().removeIf(since -> now - since > config.forgetAfter);
		List<FlockyAgent> check = new ArrayList<>();
		check.addAll(known);
		check.addAll(heardFrom);
		check.addAll(lost.keySet());
		for (FlockyAgent seed : seeds)
			if (!known.contains(seed))
				check.add(seed);
		heardFrom.clear();
		round = new Round();
		for (FlockyAgent candidate : check)
			probe(round, candidate);
		if (flocky.hasCloudPlacement(this))
			pingCloud();
		if (round.pending == 0)
			endRound(round);
	}

	private void probe(Round current, FlockyAgent candidate) {
		if (candidate == this || !current.queued.add(candidate))
			return;
		current.pending++;
		double sent = ctx.now();
		ctx.rpc(node, candidate.node, config.pingBytes, new Ping(this), config.discoveryTimeout,
				reply -> pinged(current, candidate, ctx.now() - sent),
				() -> pinged(current, candidate, Double.POSITIVE_INFINITY));
	}

	private void pinged(Round current, FlockyAgent candidate, double d) {
		if (current != round)
			return;
		current.measured.put(candidate, d);
		if (d == Double.POSITIVE_INFINITY) {
			// No answer: the node is not in range, and it is retried until it is
			// forgotten.
			if (known.remove(candidate))
				lost.putIfAbsent(candidate, ctx.now());
			distance.remove(candidate);
			summaries.remove(candidate);
		} else {
			lost.remove(candidate);
			double dMax = adjustedDistance(known.size());
			if (d <= dMax) {
				known.add(candidate);
				distance.put(candidate, d);
				explore(current, candidate);
				current.inReach = true;
				current.dMin = Math.min(current.dMin, d);
			} else if (current.dMin >= dMax) {
				// Still outside its own neighbourhood: explore through the closest node
				// found so far, and keep it as an anchor for the next round.
				explore(current, candidate);
				if (known.size() <= 1 || d <= current.dMin) {
					current.dMin = d;
					known.add(candidate);
					distance.put(candidate, d);
				} else
					forget(candidate);
			}
		}
		if (--current.pending == 0)
			endRound(current);
	}

	/** Asks a node for its known nodes, and pings those not queued yet. */
	private void explore(Round current, FlockyAgent target) {
		current.pending++;
		ctx.rpc(node, target.node, config.pingBytes, new GetKnown(), config.discoveryTimeout, reply -> {
			if (current == round) {
				@SuppressWarnings("unchecked")
				List<FlockyAgent> list = (List<FlockyAgent>) reply;
				for (FlockyAgent agent : list)
					probe(current, agent);
			}
			if (current == round && --current.pending == 0)
				endRound(current);
		}, () -> {
			if (current == round && --current.pending == 0)
				endRound(current);
		});
	}

	private void endRound(Round current) {
		if (current.inReach) {
			double limit = adjustedDistance(known.size());
			for (FlockyAgent agent : new ArrayList<>(known))
				if (current.measured.getOrDefault(agent, Double.POSITIVE_INFINITY) > limit)
					forget(agent);
		}
		round = null;
		if (config.proactiveMigration)
			flocky.checkPlacements(this);
	}

	private void pingCloud() {
		ctx.rpc(node, ctx.cloud(), config.pingBytes, new Ping(this), config.discoveryTimeout,
				reply -> cloudReachable = true, () -> cloudReachable = false);
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

	private void forget(FlockyAgent agent) {
		known.remove(agent);
		distance.remove(agent);
		summaries.remove(agent);
	}

	/** Answers a ping: a node not known yet is remembered for the next round. */
	StrategyContext.Reply onPing(FlockyAgent from) {
		if (!known.contains(from))
			heardFrom.add(from);
		return new StrategyContext.Reply(Boolean.TRUE, config.pingBytes);
	}

	/** Answers a request for the known nodes. */
	StrategyContext.Reply onGetKnown() {
		return new StrategyContext.Reply(new ArrayList<>(known),
				config.pingBytes + (long) config.nodeEntryBytes * known.size());
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
	 * Runs one repository round: asks every known node for its summary, and for the
	 * definitions it does not know yet. Frees the clients whose lease ran out.
	 */
	void syncMetadata() {
		if (!ctx.isUp(node))
			return;
		summaries.keySet().retainAll(known);
		for (FlockyAgent agent : new ArrayList<>(known))
			ctx.rpc(node, agent.node, config.pingBytes, new GetSummary(), config.discoveryTimeout, reply -> {
				SummaryReply summary = (SummaryReply) reply;
				if (known.contains(agent))
					summaries.put(agent, summary.summary);
				for (Definition definition : summary.definitions)
					if (!definitions.containsKey(definition.id))
						ctx.rpc(node, agent.node, config.pingBytes, new GetManifest(definition),
								config.discoveryTimeout, manifest -> learn((Definition) manifest), () -> {
								});
			}, () -> {
			});
		expireLeases();
	}

	/** Answers a request for this node's summary. */
	StrategyContext.Reply onGetSummary() {
		return new StrategyContext.Reply(new SummaryReply(summary(), new ArrayList<>(definitions.values())),
				config.summaryBaseBytes + (long) config.definitionIdBytes * (definitions.size() + instances.size()));
	}

	/** Answers a request for a manifest. */
	StrategyContext.Reply onGetManifest(Definition definition) {
		return definitions.containsKey(definition.id) ? new StrategyContext.Reply(definition, config.manifestBytes)
				: null;
	}

	/** Makes a definition known to this node, as an operator would introduce it. */
	void learn(Definition definition) {
		definitions.putIfAbsent(definition.id, definition);
	}

	/** @return the definitions this node knows */
	public Iterable<Definition> definitions() {
		return definitions.values();
	}

	/**
	 * @param agent a known node
	 * @return its last summary, or {@code null} when none was received yet; the
	 *         summary of this node is always fresh
	 */
	public NodeSummary summaryOf(FlockyAgent agent) {
		return agent == this ? summary() : summaries.get(agent);
	}

	/** @return a summary of this node as it is now */
	NodeSummary summary() {
		Set<Integer> open = new HashSet<>();
		for (Map.Entry<Definition, Map<Integer, Double>> instance : instances.entrySet())
			if (instance.getValue().size() < config.maxClientsPerInstance)
				open.add(instance.getKey().id);
		return new NodeSummary(traits, memoryCapacity, freeMemory(), open, ctx.now());
	}

	// ----------------------------------------------------------- deployment

	/**
	 * The deployment API: runs the definition for one more client, on an instance
	 * that already runs it when one has room, or on a new one, which the node starts.
	 *
	 * @param definition the definition to run
	 * @param client     the device that will call it
	 * @return whether the node took it
	 */
	boolean deploy(Definition definition, int client) {
		if (!ctx.isUp(node) || !definition.fits(traits))
			return false;
		Map<Integer, Double> clients = instances.get(definition);
		if (clients != null && clients.containsKey(client)) {
			clients.put(client, ctx.now());
			return true;
		}
		if (clients != null) {
			if (clients.size() >= config.maxClientsPerInstance)
				return false;
			clients.put(client, ctx.now());
			return true;
		}
		if (freeMemory() < definition.memory || !ctx.startInstance(node, definition.service, null))
			return false;
		clients = new HashMap<>();
		clients.put(client, ctx.now());
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
		Map<Integer, Double> clients = instances.get(definition);
		if (clients == null || clients.remove(client) == null || !clients.isEmpty())
			return;
		instances.remove(definition);
		memoryUsed -= definition.memory;
		boolean serviceStillRuns = false;
		for (Definition other : instances.keySet())
			if (other.service == definition.service)
				serviceStillRuns = true;
		if (!serviceStillRuns)
			ctx.stopInstance(node, definition.service);
	}

	/** Renews the lease of a client of an instance that runs here. */
	void renew(Definition definition, int client) {
		Map<Integer, Double> clients = instances.get(definition);
		if (clients != null && clients.containsKey(client))
			clients.put(client, ctx.now());
	}

	/** Marks the clients of a service on this node as served now. */
	void served(Service service, int client) {
		for (Map.Entry<Definition, Map<Integer, Double>> instance : instances.entrySet())
			if (instance.getKey().service == service && instance.getValue().containsKey(client))
				instance.getValue().put(client, ctx.now());
	}

	/** Frees the clients not served for {@code deployment.orphan_timeout_s}. */
	private void expireLeases() {
		double now = ctx.now();
		for (Map.Entry<Definition, Map<Integer, Double>> instance : new ArrayList<>(instances.entrySet()))
			for (Map.Entry<Integer, Double> client : new ArrayList<>(instance.getValue().entrySet()))
				if (now - client.getValue() > config.orphanTimeout) {
					orphansReleased++;
					release(instance.getKey(), client.getKey());
				}
	}

	/** @return whether an instance of the definition runs here with room for one more client */
	boolean hasOpenInstance(Definition definition) {
		Map<Integer, Double> clients = instances.get(definition);
		return clients != null && clients.size() < config.maxClientsPerInstance;
	}

	/** Forgets everything: the server crashed and runs again. */
	void restart() {
		instances.clear();
		memoryUsed = 0;
		known.clear();
		distance.clear();
		lost.clear();
		summaries.clear();
		heardFrom.addAll(seeds);
		round = null;
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
}
