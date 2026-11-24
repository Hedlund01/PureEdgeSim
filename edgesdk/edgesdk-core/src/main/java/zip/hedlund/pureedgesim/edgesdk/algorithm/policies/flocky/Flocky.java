package zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import zip.hedlund.pureedgesim.edgesdk.algorithm.NodeLocal;
import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Response;
import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.model.NodeDecl;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;

/**
 * Flocky: decentralized, intent-based orchestration with the Open Application
 * Model (Goethals et al., IEEE Transactions on Services Computing 19(1), 2026).
 * Every node runs the same services and decides with what it learned itself:
 * <ul>
 * <li><b>Discovery</b> ({@link FlockyDiscovery}): finds the nodes within a
 * round-trip range by asking known nodes for the nodes they know.</li>
 * <li><b>Repository</b>: every {@code repositoryPeriod} seconds, fetches the
 * {@link NodeSummary} of every discovered node (resources, traits, workload
 * types, deployments) and merges the {@link ComponentDefinition}s they hold, so
 * definitions spread from node to node.</li>
 * <li><b>Swirly</b>: a node's application is the set of {@link Component}s it
 * needs; a task of workload {@code w} is a request to component {@code w}. For
 * each component without a deployment, Swirly matches definitions and nodes on
 * workload type, required traits and free resources, puts nodes that already run
 * a suitable definition first, ranks the rest with a {@link QoeEvaluator}, and
 * asks them in turn to deploy. Deployments are re-checked every
 * {@code swirlyPeriod} seconds and migrated when their node leaves the
 * neighbourhood, loses the deployment or stops meeting the traits.</li>
 * <li><b>Deployment</b>: accepts a definition when it fits in what is not
 * reserved yet, counts its clients, and frees it when the last one leaves.</li>
 * </ul>
 * Tasks go to the device's current deployment of their component. While a
 * component has no deployment, or when an attempt gets no result within
 * {@code retryFraction} of the deadline (the deployment is then dropped and
 * replaced), the task runs on the device itself; {@link #rejectWithoutDeployment()}
 * makes it fail instead, as a Flocky deployment request would. Nodes never deploy
 * to themselves.
 *
 * <pre>{@code
 * Candidate.of("flocky", () -> new Flocky()
 *         .component(Component.of("video").requires("secure-runtime"))
 *         .definition(ComponentDefinition.of("video-gpu", "video", 4, 2_048).provides("secure-runtime"))
 *         .traits(Select.name("rsu-*"), "green"));
 * }</pre>
 */
public class Flocky extends OrchestrationPolicy {

	record GetSummary() {
	}

	record Deploy(Component component, ComponentDefinition definition) {
	}

	record Undeploy(String definition) {
	}

	private static final GetSummary GET_SUMMARY = new GetSummary();

	/** A component of a node's application, deployed on another node. */
	public record Deployment(NodeRef node, ComponentDefinition definition, double since) {
	}

	private static final class NodeState {
		// Repository
		final Map<String, ComponentDefinition> definitions = new TreeMap<>();
		final Map<NodeRef, NodeSummary> metadata = new TreeMap<>();
		// Deployment service
		final Map<String, ComponentDefinition> hosted = new TreeMap<>();
		final Map<String, Set<NodeRef>> clients = new HashMap<>();
		double reservedCores;
		double reservedRamMb;
		// Swirly
		final Set<String> application = new LinkedHashSet<>();
		final Map<String, Deployment> deployments = new TreeMap<>();
		final Set<String> orchestrating = new TreeSet<>();
		final Map<NodeRef, Double> avoidUntil = new HashMap<>();
	}

	private final Map<String, Component> components = new LinkedHashMap<>();
	private final Map<String, ComponentDefinition> definitions = new LinkedHashMap<>();
	private final Map<String, Select> knownBy = new HashMap<>();
	private final List<Map.Entry<Select, Set<String>>> traitRules = new ArrayList<>();
	private final List<Map.Entry<Select, Set<String>>> workloadTypeRules = new ArrayList<>();
	private final List<Map.Entry<Select, List<String>>> applicationRules = new ArrayList<>();
	private Select deploymentNodes = Select.edges().or(Select.tier(Tier.CLOUD));
	private QoeEvaluator evaluator = QoeEvaluator.scored();
	private double maxRtt = 0.1;
	private double assumedDensity = 5;
	private double pingTimeout = 0.5;
	private double discoveryPeriod = 5;
	private double repositoryPeriod = 5;
	private double swirlyPeriod = 2;
	private double deployTimeout = 0.5;
	private double retryFraction = 0.6;
	private boolean fallbackLocal = true;

	private FlockyDiscovery discovery;
	private NodeLocal<NodeState> state;
	private final Map<NodeRef, Set<String>> nodeTraits = new HashMap<>();
	private final Map<NodeRef, Set<String>> nodeWorkloadTypes = new HashMap<>();
	private final Set<NodeRef> deployers = new TreeSet<>();
	private final Map<NodeRef, List<ComponentDefinition>> bootDefinitions = new HashMap<>();
	private final Map<NodeRef, List<String>> bootApplications = new HashMap<>();

	// ---- configuration ----------------------------------------------------

	/** Declares a component's trait requirements; a workload with no component declared has none. */
	public Flocky component(Component component) {
		components.put(component.name(), component);
		return this;
	}

	/** Adds a definition that every node holds when it boots. */
	public Flocky definition(ComponentDefinition definition) {
		return definition(definition, Select.all());
	}

	/** Adds a definition that the selected nodes hold when they boot; the Repository spreads it. */
	public Flocky definition(ComponentDefinition definition, Select knownBy) {
		definitions.put(definition.name(), definition);
		this.knownBy.put(definition.name(), knownBy);
		return this;
	}

	/** Gives the selected nodes traits, as their capability providers would advertise them. */
	public Flocky traits(Select nodes, String... traits) {
		traitRules.add(Map.entry(nodes, Set.of(traits)));
		return this;
	}

	/**
	 * Lets the selected nodes run more workload types; every node runs
	 * {@value ComponentDefinition#CONTAINER}.
	 */
	public Flocky workloadTypes(Select nodes, String... types) {
		workloadTypeRules.add(Map.entry(nodes, Set.of(types)));
		return this;
	}

	/** The nodes that run the Deployment service; edge nodes and clouds by default. */
	public Flocky deploymentNodes(Select nodes) {
		this.deploymentNodes = nodes;
		return this;
	}

	/**
	 * Gives the selected nodes an application to deploy from the start. Without
	 * one, a component joins a node's application with its first task.
	 */
	public Flocky application(Select nodes, String... components) {
		applicationRules.add(Map.entry(nodes, List.of(components)));
		return this;
	}

	/** The evaluator that ranks nodes; {@link QoeEvaluator#scored()} by default. */
	public Flocky evaluator(QoeEvaluator evaluator) {
		this.evaluator = evaluator;
		return this;
	}

	/**
	 * The paper evaluates ranges of 10 and 20 ms; the default here is wider
	 * because the SDK advances transfers every 0.02 s, so one hop already costs
	 * about 0.04 s of round trip.
	 *
	 * @param maxRtt         discovery range, round-trip seconds (0.1)
	 * @param assumedDensity nodes expected within range (5)
	 * @param period         seconds between discovery rounds (5)
	 */
	public Flocky discovery(double maxRtt, double assumedDensity, double period) {
		this.maxRtt = maxRtt;
		this.assumedDensity = assumedDensity;
		this.discoveryPeriod = period;
		return this;
	}

	/**
	 * @param repositoryPeriod seconds between summary fetches (5)
	 * @param swirlyPeriod     seconds between deployment checks (2)
	 * @param requestTimeout   seconds to wait for a ping, node list or deployment answer (0.5)
	 */
	public Flocky periods(double repositoryPeriod, double swirlyPeriod, double requestTimeout) {
		this.repositoryPeriod = repositoryPeriod;
		this.swirlyPeriod = swirlyPeriod;
		this.pingTimeout = requestTimeout;
		this.deployTimeout = requestTimeout;
		return this;
	}

	/** Retries a task elsewhere after this fraction of its deadline without a result (0.6). */
	public Flocky retryFraction(double fraction) {
		this.retryFraction = fraction;
		return this;
	}

	/** Fails a task that has no deployment to go to, instead of running it on the device. */
	public Flocky rejectWithoutDeployment() {
		this.fallbackLocal = false;
		return this;
	}

	// ---- lifecycle ----------------------------------------------------------

	@Override
	public void onStart(SdkContext ctx) {
		discovery = new FlockyDiscovery(ctx, maxRtt, assumedDensity, pingTimeout, discoveryPeriod);
		var topology = ctx.topology();
		for (NodeDecl n : topology.nodes()) {
			nodeTraits.put(n.ref(), new TreeSet<>());
			nodeWorkloadTypes.put(n.ref(), new TreeSet<>(Set.of(ComponentDefinition.CONTAINER)));
			bootDefinitions.put(n.ref(), new ArrayList<>());
			bootApplications.put(n.ref(), new ArrayList<>());
		}
		for (var rule : traitRules)
			for (NodeRef n : rule.getKey().resolve(topology))
				nodeTraits.get(n).addAll(rule.getValue());
		for (var rule : workloadTypeRules)
			for (NodeRef n : rule.getKey().resolve(topology))
				nodeWorkloadTypes.get(n).addAll(rule.getValue());
		for (var rule : applicationRules)
			for (NodeRef n : rule.getKey().resolve(topology))
				bootApplications.get(n).addAll(rule.getValue());
		for (ComponentDefinition d : definitions.values())
			for (NodeRef n : knownBy.get(d.name()).resolve(topology))
				bootDefinitions.get(n).add(d);
		deployers.addAll(deploymentNodes.resolve(topology));
		state = ctx.nodeLocal(n -> {
			NodeState s = new NodeState();
			bootDefinitions.get(n).forEach(d -> s.definitions.put(d.name(), d));
			s.application.addAll(bootApplications.get(n));
			return s;
		});
		for (NodeDecl n : topology.nodes())
			boot(n.ref());
	}

	@Override
	public void onNodeRestarted(NodeRef at) {
		boot(at);
	}

	private void boot(NodeRef at) {
		state.at(at);
		discovery.start(at, ctx.topology().neighbours(at));
		ctx.schedule(at, ctx.rng().nextDouble() * repositoryPeriod, () -> {
			refreshRepository(at);
			ctx.every(at, repositoryPeriod, () -> refreshRepository(at));
		});
		ctx.schedule(at, ctx.rng().nextDouble() * swirlyPeriod, () -> {
			evolve(at);
			ctx.every(at, swirlyPeriod, () -> evolve(at));
		});
	}

	// ---- task placement -----------------------------------------------------

	@Override
	public Placement place(NodeRef device, TaskRequest task) {
		NodeState s = state.at(device);
		if (s.application.add(task.workload()))
			orchestrate(device, task.workload());
		Deployment d = s.deployments.get(task.workload());
		if (d != null)
			return Placement.on(d.node()).withRetryAfter((task.deadline() - task.createdAt()) * retryFraction);
		return withoutDeployment();
	}

	@Override
	public Placement onAttemptTimedOut(NodeRef device, TaskRequest task, NodeRef tried, int attempt) {
		if (tried.equals(device))
			return Placement.reject();
		NodeState s = state.at(device);
		Deployment d = s.deployments.get(task.workload());
		if (d != null && d.node().equals(tried)) {
			s.avoidUntil.put(tried, ctx.now() + discoveryPeriod);
			drop(device, task.workload());
			orchestrate(device, task.workload());
		}
		return withoutDeployment();
	}

	private Placement withoutDeployment() {
		return fallbackLocal ? Placement.local() : Placement.reject();
	}

	// ---- Repository service -------------------------------------------------

	private void refreshRepository(NodeRef at) {
		Set<NodeRef> known = discovery.known(at);
		NodeState s = state.at(at);
		s.metadata.keySet().retainAll(known);
		for (NodeRef n : known)
			ctx.request(at, n, 32, GET_SUMMARY, pingTimeout, reply -> {
				NodeSummary summary = (NodeSummary) reply.payload();
				s.metadata.put(n, summary);
				for (ComponentDefinition d : summary.definitions())
					s.definitions.putIfAbsent(d.name(), d);
			}, () -> {
			});
	}

	private NodeSummary summary(NodeRef at) {
		NodeState s = state.at(at);
		var spec = ctx.topology().node(at).spec();
		Map<String, Integer> deployed = new TreeMap<>();
		s.clients.forEach((def, c) -> deployed.put(def, c.size()));
		return new NodeSummary(at, ctx.now(), deployers.contains(at), spec.compute().cores(),
				spec.memory().ramMb(), s.reservedCores, s.reservedRamMb, ctx.local(at).load(), nodeTraits.get(at),
				nodeWorkloadTypes.get(at), deployed, List.copyOf(s.definitions.values()));
	}

	// ---- Swirly service -----------------------------------------------------

	/** Evolution: drops deployments that no longer hold, and deploys what is missing. */
	private void evolve(NodeRef at) {
		NodeState s = state.at(at);
		Set<NodeRef> known = discovery.known(at);
		for (String c : List.copyOf(s.application)) {
			Deployment d = s.deployments.get(c);
			if (d != null) {
				NodeSummary n = s.metadata.get(d.node());
				boolean gone = !known.contains(d.node());
				boolean lost = n != null && n.time() > d.since() && !n.hasDeployed(d.definition());
				boolean violated = n != null && !n.matches(component(c), d.definition());
				if (gone || lost || violated)
					drop(at, c);
			}
			if (!s.deployments.containsKey(c))
				orchestrate(at, c);
		}
	}

	private Component component(String name) {
		return components.getOrDefault(name, Component.of(name));
	}

	private void orchestrate(NodeRef at, String c) {
		NodeState s = state.at(at);
		if (s.deployments.containsKey(c) || !s.orchestrating.add(c))
			return;
		Component component = component(c);
		List<ComponentDefinition> defs = s.definitions.values().stream().filter(d -> d.baseComponent().equals(c))
				.toList();
		if (defs.isEmpty() && definitions.values().stream().noneMatch(d -> d.baseComponent().equals(c)))
			defs = List.of(ComponentDefinition.generic(c));
		double range = discovery.range(at);
		List<QoeEvaluator.Option> options = new ArrayList<>();
		for (NodeSummary n : s.metadata.values()) {
			if (n.node().equals(at) || s.avoidUntil.getOrDefault(n.node(), 0.0) > ctx.now())
				continue;
			for (ComponentDefinition d : defs)
				if (n.matches(component, d) && n.fits(d))
					options.add(new QoeEvaluator.Option(component, d, n, discovery.rtt(at, n.node()), range));
		}
		Map<QoeEvaluator.Option, Double> score = new HashMap<>();
		options.forEach(o -> score.put(o, evaluator.score(o)));
		// reuse first, then by score
		options.sort(Comparator.comparing((QoeEvaluator.Option o) -> !o.node().hasDeployed(o.definition()))
				.thenComparing(o -> -score.get(o)).thenComparing(o -> o.node().node())
				.thenComparing(o -> o.definition().name()));
		tryDeploy(at, c, options, 0);
	}

	private void tryDeploy(NodeRef at, String c, List<QoeEvaluator.Option> options, int i) {
		NodeState s = state.at(at);
		if (i >= options.size()) {
			s.orchestrating.remove(c);
			ctx.metrics().add("flocky.deploy_failures", 1);
			return;
		}
		QoeEvaluator.Option o = options.get(i);
		NodeRef target = o.node().node();
		ctx.request(at, target, 64 + ComponentDefinition.BYTES, new Deploy(o.component(), o.definition()),
				deployTimeout, reply -> {
					if (Boolean.TRUE.equals(reply.payload())) {
						s.orchestrating.remove(c);
						s.deployments.put(c, new Deployment(target, o.definition(), ctx.now()));
						ctx.metrics().add("flocky.deployments", 1);
					} else
						tryDeploy(at, c, options, i + 1);
				}, () -> tryDeploy(at, c, options, i + 1));
	}

	private void drop(NodeRef at, String c) {
		Deployment d = state.at(at).deployments.remove(c);
		if (d == null)
			return;
		ctx.send(at, d.node(), 64, new Undeploy(d.definition().name()));
		ctx.metrics().add("flocky.migrations", 1);
	}

	// ---- Deployment service -------------------------------------------------

	private boolean deploy(NodeRef at, NodeRef client, Component component, ComponentDefinition def) {
		if (!deployers.contains(at) || at.equals(client))
			return false;
		NodeSummary me = summary(at);
		if (!me.matches(component, def))
			return false;
		NodeState s = state.at(at);
		if (!s.hosted.containsKey(def.name())) {
			if (!me.fits(def))
				return false;
			s.hosted.put(def.name(), def);
			s.reservedCores += def.cores();
			s.reservedRamMb += def.ramMb();
		}
		s.clients.computeIfAbsent(def.name(), k -> new TreeSet<>()).add(client);
		return true;
	}

	private void undeploy(NodeRef at, NodeRef client, String def) {
		NodeState s = state.at(at);
		Set<NodeRef> c = s.clients.get(def);
		if (c == null || !c.remove(client) || !c.isEmpty())
			return;
		s.clients.remove(def);
		ComponentDefinition d = s.hosted.remove(def);
		s.reservedCores -= d.cores();
		s.reservedRamMb -= d.ramMb();
	}

	// ---- messages -----------------------------------------------------------

	@Override
	public Object onRequest(NodeRef at, NodeRef from, Object request) {
		Object discovered = discovery.handle(at, from, request);
		if (discovered != null)
			return discovered;
		if (request instanceof GetSummary) {
			NodeSummary s = summary(at);
			return Response.of(s, s.bytes());
		}
		if (request instanceof Deploy d)
			return deploy(at, from, d.component(), d.definition());
		return super.onRequest(at, from, request);
	}

	@Override
	public void onMessage(NodeRef at, NodeRef from, Object payload) {
		if (payload instanceof Undeploy u)
			undeploy(at, from, u.definition());
	}

	// ---- introspection ------------------------------------------------------

	/** @return the discovery service, e.g. for tests */
	public FlockyDiscovery discovery() {
		return discovery;
	}

	/** @return the current deployments of a node's application, by component */
	public Map<String, Deployment> deployments(NodeRef at) {
		return Map.copyOf(state.at(at).deployments);
	}

	/** @return the definitions deployed on a node, by name, with their clients */
	public Map<String, Set<NodeRef>> hosted(NodeRef at) {
		Map<String, Set<NodeRef>> out = new TreeMap<>();
		state.at(at).clients.forEach((d, c) -> out.put(d, Set.copyOf(c)));
		return out;
	}

	/** @return the definitions in a node's repository */
	public Set<String> repository(NodeRef at) {
		return Set.copyOf(state.at(at).definitions.keySet());
	}
}
