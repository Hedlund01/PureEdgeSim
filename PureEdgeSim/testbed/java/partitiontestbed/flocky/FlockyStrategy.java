package partitiontestbed.flocky;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

import partitiontestbed.Attempt;
import partitiontestbed.EdgeServer;
import partitiontestbed.Job;
import partitiontestbed.OrchestrationStrategy;
import partitiontestbed.Service;
import partitiontestbed.StrategyContext;
import partitiontestbed.Testbed;
import partitiontestbed.TestbedMetrics;
import partitiontestbed.Trait;
import partitiontestbed.flocky.FlockyAgent.Deploy;
import partitiontestbed.flocky.FlockyAgent.GetKnown;
import partitiontestbed.flocky.FlockyAgent.GetManifest;
import partitiontestbed.flocky.FlockyAgent.GetSummary;
import partitiontestbed.flocky.FlockyAgent.Ping;
import partitiontestbed.flocky.FlockyAgent.Release;
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
 * server of each device, deploys its service with what that server has learnt,
 * and every exchange is a message on the simulated network.
 * <p>
 * A deployment runs in the order of section III-D of the paper, while the tasks
 * of the device wait at its access server:
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
 * A call that gets no reply within {@code swirly.rpc_timeout_ms} moves on to the
 * next candidate, and a candidate whose summary was stale refuses.</li>
 * <li>When no edge server accepts, the cloud is called.</li>
 * </ol>
 * Its evolution step moves a service away as soon as its host leaves the view of
 * the device's access server, or the cloud stops answering it. A host that gets a
 * task for an instance it no longer runs tells the server that sent it, which
 * deploys again. Flocky defines nothing more for a task that is lost: no retry,
 * no backup; a lost task is lost.
 *
 * @see FlockyConfig
 */
public class FlockyStrategy extends OrchestrationStrategy {

	/** Where the service of a device runs, as its access server knows it. */
	private static final class Placement {
		/** The host, or {@code null} for the cloud. */
		FlockyAgent host;
		Definition definition;
		boolean ready;
		/** Tasks waiting for the deployment. */
		final List<Job> waiting = new ArrayList<>();
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

	/** One run of Swirly for one device. */
	private final class Plan {
		final FlockyAgent home;
		final ComputingNode device;
		final Service service;
		final Placement placement;
		final double started;
		List<Definition> definitions;
		List<Candidate> candidates;
		int next;
		int rpcs;
		int unreachable;
		int refused;
		boolean reused;
		String outcome;

		Plan(FlockyAgent home, ComputingNode device, Service service, Placement placement) {
			this.home = home;
			this.device = device;
			this.service = service;
			this.placement = placement;
			this.started = ctx.now();
		}
	}

	private final boolean scored;
	FlockyConfig config;
	private Oam.Catalog catalog;
	private final List<FlockyAgent> agents = new ArrayList<>();
	private final Map<EdgeServer, FlockyAgent> agentOf = new HashMap<>();
	/** On each access server, where the service of each of its devices runs. */
	private final Map<FlockyAgent, Map<ComputingNode, Placement>> placements = new HashMap<>();

	// What Flocky did, for the report and its own file
	private int deployments;
	private int failedDeployments;
	private int reusedInstances;
	private int rpcs;
	private int unreachable;
	private int refusals;
	private int migrations;
	private int noInstance;
	private final List<String> deploymentLog = new ArrayList<>();

	/**
	 * @param scored whether Swirly ranks with the scored evaluator rather than by
	 *               latency alone
	 */
	public FlockyStrategy(boolean scored) {
		this.scored = scored;
	}

	@Override
	public void initialize() {
		this.config = new FlockyConfig(Testbed.settingsFile("flocky.properties"), ctx.servers().size());
		catalog = Oam.Catalog.generate(config, ctx.services(), ctx.random());
		for (EdgeServer server : ctx.servers()) {
			FlockyAgent agent = new FlockyAgent(this, ctx, server);
			agents.add(agent);
			agentOf.put(server, agent);
		}
		for (FlockyAgent agent : agents) {
			List<FlockyAgent> others = new ArrayList<>(agents);
			others.remove(agent);
			Collections.shuffle(others, ctx.random());
			for (int i = 0; i < config.bootstrapPeers; i++)
				agent.addSeed(others.get(i));
		}
		// Every definition is introduced on a few servers, or on all of them, and
		// spreads through the repositories from there.
		for (Definition definition : catalog.definitions) {
			List<FlockyAgent> shuffled = new ArrayList<>(agents);
			Collections.shuffle(shuffled, ctx.random());
			for (int i = 0; i < config.seedNodesPerDefinition; i++)
				shuffled.get(i).learn(definition);
		}
		for (FlockyAgent agent : agents) {
			ctx.schedule(ctx.random().nextDouble() * config.discoveryInterval, () -> discoveryRound(agent));
			ctx.schedule(ctx.random().nextDouble() * config.repositoryInterval, () -> repositoryRound(agent));
		}
	}

	private void discoveryRound(FlockyAgent agent) {
		agent.discover();
		ctx.schedule(config.discoveryInterval, () -> discoveryRound(agent));
	}

	private void repositoryRound(FlockyAgent agent) {
		agent.syncMetadata();
		renewLeases(agent);
		ctx.schedule(config.repositoryInterval, () -> repositoryRound(agent));
	}

	/**
	 * Renews the lease of every instance this server placed for its devices: a host
	 * that stops hearing the renewals, because a cut keeps them away, frees the
	 * client once {@code deployment.orphan_timeout_s} has passed.
	 */
	private void renewLeases(FlockyAgent home) {
		if (!ctx.isUp(home.node))
			return;
		for (Map.Entry<ComputingNode, Placement> entry : placements.getOrDefault(home, Map.of()).entrySet()) {
			Placement placement = entry.getValue();
			if (!placement.ready || placement.host == null)
				continue;
			Renew renew = new Renew(placement.definition, entry.getKey().getId());
			if (placement.host == home)
				home.renew(renew.definition, renew.client);
			else
				ctx.send(home.node, placement.host.node, config.pingBytes, renew);
		}
	}

	/** Renews the lease of a client on its host. */
	private static final class Renew {
		final Definition definition;
		final int client;

		Renew(Definition definition, int client) {
			this.definition = definition;
			this.client = client;
		}
	}

	/** @return the agents, one per edge server */
	List<FlockyAgent> agents() {
		return Collections.unmodifiableList(agents);
	}

	// ------------------------------------------------------------- tasks

	@Override
	public void onTaskArrived(ComputingNode node, Job job) {
		FlockyAgent here = agentOf.get(ctx.serverOf(node));
		if (here == null || job.followsStage()) {
			// A later stage of an edge pipeline runs where the stage before it ran, on
			// the instance placed for the device.
			ctx.dispatch(job, node, node);
			return;
		}
		Map<ComputingNode, Placement> ofHome = placements.computeIfAbsent(here, key -> new LinkedHashMap<>());
		Placement placement = ofHome.get(job.device());
		if (placement != null && placement.ready) {
			ctx.dispatch(job, node, placement.host == null ? ctx.cloud() : placement.host.node);
			return;
		}
		if (placement != null) {
			placement.waiting.add(job);
			return;
		}
		placement = new Placement();
		placement.waiting.add(job);
		ofHome.put(job.device(), placement);
		Plan plan = new Plan(here, job.device(), job.service(), placement);
		ctx.schedule(config.processingTime, () -> plan(plan));
	}

	@Override
	public void onNoInstance(ComputingNode host, Attempt attempt) {
		// The deployment service runs no instance for the task: it tells the server
		// that sent it, which deploys the service again for the next task.
		noInstance++;
		ctx.send(host, attempt.from(), config.pingBytes, new Lost(attempt.job().device(), host));
	}

	@Override
	public void onExecuted(ComputingNode host, Attempt attempt) {
		FlockyAgent agent = agentOf.get(ctx.serverOf(host));
		if (agent != null)
			agent.served(attempt.job().service(), attempt.job().device().getId());
		super.onExecuted(host, attempt);
	}

	/** A host tells a server that the instance it sent a task to is gone. */
	private static final class Lost {
		final ComputingNode device;
		final ComputingNode host;

		Lost(ComputingNode device, ComputingNode host) {
			this.device = device;
			this.host = host;
		}
	}

	// ----------------------------------------------------------- deployment

	private void plan(Plan plan) {
		plan.definitions = new ArrayList<>();
		for (Definition definition : plan.home.definitions())
			if (definition.service == plan.service)
				plan.definitions.add(definition);
		if (plan.definitions.isEmpty()) {
			plan.outcome = "NO_DEFINITION";
			fail(plan);
			return;
		}
		plan.candidates = candidates(plan.home, plan.definitions);
		tryNext(plan);
	}

	private void tryNext(Plan plan) {
		if (plan.next >= plan.candidates.size()) {
			if (config.cloudFallback)
				tryCloud(plan);
			else {
				plan.outcome = plan.candidates.isEmpty() ? "NO_CANDIDATE" : "EXHAUSTED";
				fail(plan);
			}
			return;
		}
		Candidate candidate = plan.candidates.get(plan.next++);
		plan.rpcs++;
		int client = plan.device.getId();
		if (candidate.agent == plan.home) {
			boolean running = candidate.agent.hasOpenInstance(candidate.definition);
			if (candidate.agent.deploy(candidate.definition, client)) {
				plan.reused = running;
				placed(plan, candidate.agent, candidate.definition, "LOCAL");
			} else {
				plan.refused++;
				tryNext(plan);
			}
			return;
		}
		ctx.rpc(plan.home.node, candidate.agent.node, config.manifestBytes, new Deploy(candidate.definition, client),
				config.deploymentTimeout, reply -> {
					if (Boolean.TRUE.equals(reply)) {
						plan.reused = candidate.open;
						placed(plan, candidate.agent, candidate.definition, "EDGE");
					} else {
						plan.refused++;
						tryNext(plan);
					}
				}, () -> {
					plan.unreachable++;
					tryNext(plan);
				});
	}

	private void tryCloud(Plan plan) {
		Definition fitting = null;
		for (Definition definition : plan.definitions)
			if (definition.fits(ctx.cloudTraits())) {
				fitting = definition;
				break;
			}
		if (fitting == null) {
			plan.outcome = plan.candidates.isEmpty() ? "NO_CANDIDATE" : "EXHAUSTED";
			fail(plan);
			return;
		}
		Definition definition = fitting;
		plan.rpcs++;
		ctx.rpc(plan.home.node, ctx.cloud(), config.manifestBytes, new Deploy(definition, plan.device.getId()),
				config.deploymentTimeout, reply -> {
					if (Boolean.TRUE.equals(reply))
						placed(plan, null, definition, "CLOUD");
					else {
						plan.outcome = "CLOUD_REFUSED";
						fail(plan);
					}
				}, () -> {
					plan.unreachable++;
					plan.outcome = "CLOUD_UNREACHABLE";
					fail(plan);
				});
	}

	private void placed(Plan plan, FlockyAgent host, Definition definition, String outcome) {
		plan.outcome = outcome;
		Placement placement = plan.placement;
		if (placements.getOrDefault(plan.home, Map.of()).get(plan.device) != placement) {
			// The placement was dropped while Swirly ran: give the instance back.
			release(plan.home, host, definition, plan.device);
			log(plan, host);
			return;
		}
		placement.host = host;
		placement.definition = definition;
		placement.ready = true;
		deployments++;
		if (plan.reused)
			reusedInstances++;
		ComputingNode target = host == null ? ctx.cloud() : host.node;
		for (Job job : placement.waiting)
			ctx.dispatch(job, plan.home.node, target);
		placement.waiting.clear();
		log(plan, host);
	}

	private void fail(Plan plan) {
		failedDeployments++;
		Map<ComputingNode, Placement> ofHome = placements.get(plan.home);
		if (ofHome != null && ofHome.get(plan.device) == plan.placement)
			ofHome.remove(plan.device);
		// Its tasks are lost: Flocky has nowhere to send them.
		plan.placement.waiting.clear();
		log(plan, null);
	}

	private void log(Plan plan, FlockyAgent host) {
		rpcs += plan.rpcs;
		unreachable += plan.unreachable;
		refusals += plan.refused;
		deploymentLog.add(String.join(",", String.format(Locale.ROOT, "%.3f", plan.started),
				plan.device.getName(), ctx.deviceType(plan.device), plan.home.name(),
				plan.service.name.replace(',', ' '), plan.outcome,
				"CLOUD".equals(plan.outcome) ? "cloud" : host == null ? "" : host.name(),
				String.format(Locale.ROOT, "%.2f", (ctx.now() - plan.started) * 1000),
				plan.placement.definition == null ? "" : plan.placement.definition.name,
				String.valueOf(plan.candidates == null ? 0 : plan.candidates.size()), String.valueOf(plan.rpcs),
				String.valueOf(plan.unreachable), String.valueOf(plan.refused), String.valueOf(plan.reused)));
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

	/** Tells a host to drop a client; a host that cannot be reached frees it when its lease runs out. */
	private void release(FlockyAgent home, FlockyAgent host, Definition definition, ComputingNode device) {
		if (host == home)
			home.release(definition, device.getId());
		else if (host != null)
			ctx.send(home.node, host.node, config.pingBytes, new Release(definition, device.getId()));
	}

	/**
	 * The evolution step of Swirly: moves away every service of the devices of
	 * this server whose host has left its view, or whose cloud no longer answers,
	 * so that the next task of the device deploys it again within reach.
	 *
	 * @param home the server that just ended its discovery round
	 */
	void checkPlacements(FlockyAgent home) {
		Map<ComputingNode, Placement> ofHome = placements.get(home);
		if (ofHome == null)
			return;
		for (Map.Entry<ComputingNode, Placement> entry : new ArrayList<>(ofHome.entrySet())) {
			Placement placement = entry.getValue();
			if (!placement.ready)
				continue;
			boolean gone = placement.host == null ? !home.cloudReachable
					: placement.host != home && !home.known().contains(placement.host);
			if (!gone)
				continue;
			ofHome.remove(entry.getKey());
			release(home, placement.host, placement.definition, entry.getKey());
			migrations++;
		}
	}

	/** @return whether a server has placed a service of one of its devices on the cloud */
	boolean hasCloudPlacement(FlockyAgent home) {
		for (Placement placement : placements.getOrDefault(home, Map.of()).values())
			if (placement.ready && placement.host == null)
				return true;
		return false;
	}

	// ------------------------------------------------------------ protocol

	@Override
	public StrategyContext.Reply onRpc(ComputingNode node, ComputingNode from, Object request) {
		FlockyAgent agent = agentOf.get(ctx.serverOf(node));
		if (agent == null) {
			// The cloud answers pings and runs what it is asked to.
			if (request instanceof Ping)
				return new StrategyContext.Reply(Boolean.TRUE, config.pingBytes);
			if (request instanceof Deploy) {
				Deploy deploy = (Deploy) request;
				return new StrategyContext.Reply(ctx.startInstance(node, deploy.definition.service, null),
						config.pingBytes);
			}
			return null;
		}
		if (request instanceof Ping)
			return agent.onPing(((Ping) request).from);
		if (request instanceof GetKnown)
			return agent.onGetKnown();
		if (request instanceof GetSummary)
			return agent.onGetSummary();
		if (request instanceof GetManifest)
			return agent.onGetManifest(((GetManifest) request).definition);
		if (request instanceof Deploy) {
			Deploy deploy = (Deploy) request;
			return new StrategyContext.Reply(agent.deploy(deploy.definition, deploy.client), config.pingBytes);
		}
		return null;
	}

	@Override
	public void onMessage(ComputingNode node, ComputingNode from, Object payload) {
		FlockyAgent agent = agentOf.get(ctx.serverOf(node));
		if (payload instanceof Renew && agent != null) {
			Renew renew = (Renew) payload;
			agent.renew(renew.definition, renew.client);
		} else if (payload instanceof Release && agent != null) {
			Release release = (Release) payload;
			agent.release(release.definition, release.client);
		} else if (payload instanceof Lost && agent != null) {
			Lost lost = (Lost) payload;
			Map<ComputingNode, Placement> ofHome = placements.get(agent);
			Placement placement = ofHome == null ? null : ofHome.get(lost.device);
			ComputingNode host = placement == null || !placement.ready ? null
					: placement.host == null ? ctx.cloud() : placement.host.node;
			if (host == lost.host) {
				ofHome.remove(lost.device);
				migrations++;
			}
		}
	}

	@Override
	public boolean admitJoin(ComputingNode node, EdgeServer contact) {
		// Discovery takes in any node that pings, so Flocky's groups are open unless
		// discovery.admit_joining closes them.
		return config.admitJoining;
	}

	@Override
	public void onNodeRestarted(ComputingNode node) {
		FlockyAgent agent = agentOf.get(ctx.serverOf(node));
		if (agent == null)
			return;
		agent.restart();
		placements.remove(agent);
	}

	// -------------------------------------------------------------- output

	@Override
	public List<String> timelineColumns() {
		return List.of("known_per_node", "deployments", "migrations");
	}

	@Override
	public List<String> timelineValues() {
		int known = 0;
		for (FlockyAgent agent : agents)
			known += agent.known().size();
		return List.of(String.format(Locale.ROOT, "%.2f", (double) known / agents.size()), String.valueOf(deployments),
				String.valueOf(migrations));
	}

	@Override
	public List<String> reportLines() {
		int orphans = 0;
		for (FlockyAgent agent : agents)
			orphans += agent.orphansReleased;
		return List.of(String.format(Locale.ROOT,
				"Flocky: %d deployments, %d failed, %d reused an instance; %d deployment API calls, %d timed out, %d "
						+ "refused on a stale summary; %d services moved after their host left the view or lost its "
						+ "instance (%d tasks reached a host without one), %d clients freed when their lease ran out",
				deployments, failedDeployments, reusedInstances, rpcs, unreachable, refusals, migrations, noInstance,
				orphans));
	}

	@Override
	public void write(Path folder, String label) throws IOException {
		TestbedMetrics.write(folder.resolve(label + "_flocky_deployments.csv"),
				"time_s,device,device_type,home,service,outcome,host,latency_ms,definition,candidates,rpcs,unreachable,"
						+ "refused,reused",
				deploymentLog);
	}
}
