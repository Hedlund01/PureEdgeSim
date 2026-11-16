package partitiontestbed.flocky;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

import partitiontestbed.Deployment;
import partitiontestbed.EdgeServer;
import partitiontestbed.LiveTopology;
import partitiontestbed.PlacementStrategy;
import partitiontestbed.Service;
import partitiontestbed.Testbed;
import partitiontestbed.TestbedWorld;
import partitiontestbed.Trait;
import partitiontestbed.flocky.Oam.Definition;
import partitiontestbed.flocky.Oam.NodeSummary;

/**
 * Flocky, the decentralised intent-based orchestrator of Goethals et al. (IEEE
 * Transactions on Services Computing, 2026), as a strategy of the testbed.
 * <p>
 * Every edge server runs a {@link FlockyAgent}: a discovery service that finds the
 * servers within a maximum network latency (Algorithm 1 of the paper), a
 * metadata repository that pulls their traits, free memory and definitions, and a
 * deployment service. There is no central orchestrator: Swirly, on the access
 * server of each device, deploys its service with what that server has learnt.
 * <p>
 * A deployment runs in the order of section III-D of the paper:
 * <ol>
 * <li>The server looks for the definitions of the service in its repository. A
 * definition it has not learnt yet cannot be deployed.</li>
 * <li>Every server of its view (itself and the servers its discovery keeps) whose
 * last summary says it has the traits and the memory of a definition is a
 * candidate. Servers that already run a suitable instance with room for a client
 * come first, then the QoE evaluator ranks the rest: {@code FLOCKY_LATENCY} by the
 * distance discovery measured, the Legacy evaluator of the paper, or
 * {@code FLOCKY_SCORED}, which also weighs the free memory and the
 * {@link Trait#GREEN_ENERGY} trait.</li>
 * <li>The deployment API of each candidate is called in turn, until one accepts.
 * A call costs a round trip on the live topology, or the RPC timeout when the
 * candidate cannot be reached, and a candidate whose summary was stale refuses.
 * The time the calls took is the deployment latency.</li>
 * <li>When no edge server accepts, the cloud is tried if the server can still
 * reach it.</li>
 * </ol>
 * Its evolution step moves a service away as soon as its host leaves the view of
 * the device's access server.
 *
 * @see FlockyConfig
 */
public class FlockyStrategy implements PlacementStrategy {

	/** The result of one deployment, with what Swirly did to get it. */
	static final class Plan extends Deployment {
		Definition definition;
		boolean reused;
		int candidates;
		int rpcs;
		int unreachable;
		int refused;

		Plan(ComputingNode device, EdgeServer home, Service service) {
			super(device, home, service);
		}

		@Override
		public List<String> details() {
			return List.of(definition == null ? "" : definition.name, String.valueOf(candidates),
					String.valueOf(rpcs), String.valueOf(unreachable), String.valueOf(refused),
					String.valueOf(reused));
		}
	}

	/** A server of the view, with the definition it would run. */
	private static final class Candidate {
		final FlockyAgent agent;
		final Definition definition;
		final boolean open;
		final double score;

		Candidate(FlockyAgent agent, Definition definition, boolean open, double score) {
			this.agent = agent;
			this.definition = definition;
			this.open = open;
			this.score = score;
		}
	}

	private final boolean scored;
	TestbedWorld world;
	FlockyConfig config;
	/** The random source of Flocky, apart from the testbed's. */
	Random random;
	private Oam.Catalog catalog;
	private FlockyRuntime runtime;
	private final List<FlockyAgent> agents = new ArrayList<>();
	private final Map<EdgeServer, FlockyAgent> agentOf = new HashMap<>();

	// What Flocky did, for the report and the timeline
	private int reusedInstances;
	private int rpcs;
	private int unreachable;
	private int refusals;
	private int migrations;
	private int orphans;
	private long lastBytes;
	private double lastSample;
	private double lastAccuracy = Double.NaN;

	/**
	 * @param scored whether Swirly ranks with the scored evaluator rather than by
	 *               latency alone
	 */
	public FlockyStrategy(boolean scored) {
		this.scored = scored;
	}

	@Override
	public void initialize(TestbedWorld world) {
		this.world = world;
		this.config = new FlockyConfig(Testbed.settingsFile("flocky.properties"), world.servers().size());
		this.random = new Random(world.seed() ^ 0xF10CC7L);
		catalog = Oam.Catalog.generate(config, world.services(), random);
		for (EdgeServer server : world.servers()) {
			FlockyAgent agent = new FlockyAgent(this, server);
			agents.add(agent);
			agentOf.put(server, agent);
		}
		for (FlockyAgent agent : agents) {
			List<FlockyAgent> others = new ArrayList<>(agents);
			others.remove(agent);
			Collections.shuffle(others, random);
			for (int i = 0; i < config.bootstrapPeers; i++)
				agent.addSeed(others.get(i));
		}
		// Every definition is introduced on a few servers, or on all of them, and
		// spreads through the repositories from there.
		for (Definition definition : catalog.definitions) {
			List<FlockyAgent> shuffled = new ArrayList<>(agents);
			Collections.shuffle(shuffled, random);
			for (int i = 0; i < config.seedNodesPerDefinition; i++)
				shuffled.get(i).learn(definition);
		}
		runtime = new FlockyRuntime(this);
	}

	/** @return the agents, one per edge server */
	List<FlockyAgent> agents() {
		return Collections.unmodifiableList(agents);
	}

	/**
	 * @param server an edge server
	 * @return its agent
	 */
	FlockyAgent agentOf(EdgeServer server) {
		return agentOf.get(server);
	}

	// ----------------------------------------------------------- deployment

	@Override
	public Deployment deploy(ComputingNode device, EdgeServer homeServer, Service service) {
		FlockyAgent home = agentOf.get(homeServer);
		Plan plan = new Plan(device, homeServer, service);
		plan.delay = config.processingTime;

		List<Definition> definitions = new ArrayList<>();
		for (Definition definition : home.definitions())
			if (definition.service == service)
				definitions.add(definition);
		if (definitions.isEmpty()) {
			plan.outcome = "NO_DEFINITION";
			return plan;
		}

		List<Candidate> candidates = candidates(home, definitions);
		plan.candidates = candidates.size();
		for (Candidate candidate : candidates) {
			plan.rpcs++;
			if (candidate.agent != home) {
				double latency = world.topology().latency(home.node, candidate.agent.node);
				if (latency == Double.POSITIVE_INFINITY) {
					plan.unreachable++;
					plan.delay += config.deploymentTimeout;
					continue;
				}
				plan.delay += 2 * latency;
				home.exchange(candidate.agent, config.manifestBytes);
			}
			boolean running = candidate.agent.hasOpenInstance(candidate.definition);
			if (candidate.agent.deploy(candidate.definition, device.getId())) {
				plan.host = candidate.agent.server;
				plan.definition = candidate.definition;
				plan.reused = running;
				plan.outcome = candidate.agent == home ? "LOCAL" : "EDGE";
				break;
			}
			plan.refused++;
		}

		if (!plan.success() && config.cloudFallback)
			deployOnCloud(plan, home, definitions);
		if (!plan.success() && plan.outcome == null)
			plan.outcome = plan.candidates == 0 ? "NO_CANDIDATE" : "EXHAUSTED";
		plan.detail = plan.definition;
		rpcs += plan.rpcs;
		unreachable += plan.unreachable;
		refusals += plan.refused;
		if (plan.reused)
			reusedInstances++;
		return plan;
	}

	private void deployOnCloud(Plan plan, FlockyAgent home, List<Definition> definitions) {
		Definition fitting = null;
		for (Definition definition : definitions)
			if (definition.fits(world.config.cloudTraits)) {
				fitting = definition;
				break;
			}
		if (fitting == null)
			return;
		plan.rpcs++;
		double latency = world.topology().latency(home.node, world.cloud());
		if (latency == Double.POSITIVE_INFINITY) {
			plan.unreachable++;
			plan.delay += config.deploymentTimeout;
			plan.outcome = "CLOUD_UNREACHABLE";
			return;
		}
		plan.delay += 2 * latency;
		plan.definition = fitting;
		plan.onCloud = true;
		plan.outcome = "CLOUD";
	}

	/**
	 * @return the servers of the view that can run the service according to their
	 *         last summary, in the order their deployment API is called
	 */
	private List<Candidate> candidates(FlockyAgent home, List<Definition> definitions) {
		List<FlockyAgent> view = new ArrayList<>();
		if (config.allowLocal)
			view.add(home);
		view.addAll(home.known());

		List<Candidate> candidates = new ArrayList<>();
		for (FlockyAgent agent : view) {
			NodeSummary summary = home.summaryOf(agent);
			if (summary == null)
				continue;
			Definition best = null;
			boolean bestOpen = false;
			for (Definition definition : definitions) {
				if (!definition.fits(summary.traits))
					continue;
				boolean open = summary.openInstances.contains(definition.id);
				if (!open && summary.freeMemory < definition.memory)
					continue;
				if (best == null || open && !bestOpen || open == bestOpen && definition.memory < best.memory) {
					best = definition;
					bestOpen = open;
				}
			}
			if (best != null)
				candidates.add(new Candidate(agent, best, bestOpen, score(home, agent, summary)));
		}
		candidates.sort(Comparator.comparing((Candidate candidate) -> !candidate.open)
				.thenComparingDouble(candidate -> candidate.score));
		return candidates;
	}

	/**
	 * The QoE evaluator: lower is better.
	 */
	private double score(FlockyAgent home, FlockyAgent agent, NodeSummary summary) {
		double latency = home.distanceTo(agent) / config.maxDiscoveryDistance;
		if (!scored)
			return latency;
		double load = summary.memoryCapacity <= 0 ? 1 : 1 - summary.freeMemory / summary.memoryCapacity;
		double green = summary.traits.contains(Trait.GREEN_ENERGY) ? 0.5 : 0;
		return latency + load - green;
	}

	/**
	 * Tells the host to drop its client when it can be reached. Otherwise the
	 * instance is orphaned on the other side of the cut and keeps its memory until
	 * its lease runs out.
	 */
	@Override
	public void release(TestbedWorld.Placement placement) {
		if (placement.host == null)
			return;
		FlockyAgent host = agentOf.get(placement.host);
		if (world.topology().reachable(placement.home.node, placement.host.node))
			host.release((Definition) placement.detail, placement.device.getId());
		else {
			orphans++;
			runtime.releaseOrphan(placement);
		}
	}

	/**
	 * The evolution step of Swirly: moves away every service of the devices of
	 * this server whose host has left its view, so that the next task of the
	 * device deploys it again within reach.
	 *
	 * @param home the server that just ran its discovery round
	 */
	void checkPlacements(FlockyAgent home) {
		for (TestbedWorld.Placement placement : world.placements()) {
			if (placement.home != home.server || !placement.device.isApplicationPlaced())
				continue;
			boolean gone = placement.host == null ? !world.topology().reachable(home.node, world.cloud())
					: placement.host != home.server && !home.known().contains(agentOf.get(placement.host));
			if (!gone)
				continue;
			world.unplace(placement.device);
			migrations++;
		}
	}

	// -------------------------------------------------------------- metrics

	@Override
	public List<String> timelineColumns() {
		return List.of("discovery_accuracy", "stale_share", "known_per_node", "migrations", "control_kbps_per_node");
	}

	/**
	 * The discovery accuracy of the paper (the share of the pairs of servers within
	 * the maximum discovery distance that know each other), the share of known
	 * servers that cannot be reached any more, and the control plane traffic since
	 * the last row.
	 */
	@Override
	public List<String> timelineValues() {
		double now = world.now();
		LiveTopology topology = world.topology();
		int truth = 0;
		int found = 0;
		int known = 0;
		int stale = 0;
		long bytes = 0;
		for (FlockyAgent agent : agents) {
			bytes += agent.controlBytes();
			for (FlockyAgent other : agents)
				if (other != agent && 2 * topology.latency(agent.node, other.node) <= config.maxDiscoveryDistance) {
					truth++;
					if (agent.known().contains(other))
						found++;
				}
			for (FlockyAgent other : agent.known()) {
				known++;
				if (!topology.reachable(agent.node, other.node))
					stale++;
			}
		}
		lastAccuracy = truth == 0 ? 1 : (double) found / truth;
		double interval = Math.max(1e-9, now - lastSample);
		// Each exchange is counted at both of its ends, so the sum over the servers is
		// the traffic each server sees, summed.
		double kbpsPerNode = (bytes - lastBytes) * 8 / 1000.0 / interval / agents.size();
		lastBytes = bytes;
		lastSample = now;
		return List.of(String.format(Locale.ROOT, "%.4f", lastAccuracy),
				String.format(Locale.ROOT, "%.4f", known == 0 ? 0 : (double) stale / known),
				String.format(Locale.ROOT, "%.2f", (double) known / agents.size()), String.valueOf(migrations),
				String.format(Locale.ROOT, "%.2f", kbpsPerNode));
	}

	@Override
	public List<String> deploymentColumns() {
		return List.of("definition", "candidates", "rpcs", "unreachable", "refused", "reused");
	}

	@Override
	public List<String> reportLines() {
		return List.of(String.format(Locale.ROOT,
				"Flocky: %d deployments reused an instance; %d deployment API calls, %d timed out, %d refused on a "
						+ "stale summary; %d services moved after their host left the view, %d instances orphaned "
						+ "behind a cut; discovery accuracy at the end %.1f%%",
				reusedInstances, rpcs, unreachable, refusals, migrations, orphans, lastAccuracy * 100));
	}
}
