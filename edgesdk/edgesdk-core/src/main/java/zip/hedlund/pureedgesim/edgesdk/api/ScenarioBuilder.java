package zip.hedlund.pureedgesim.edgesdk.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import zip.hedlund.pureedgesim.edgesdk.fault.FaultModel;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScheduler;
import zip.hedlund.pureedgesim.edgesdk.fault.LinkSelect;
import zip.hedlund.pureedgesim.edgesdk.fault.NoFaults;
import zip.hedlund.pureedgesim.edgesdk.fault.Recovery;
import zip.hedlund.pureedgesim.edgesdk.model.ChannelSpec;
import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.GroupSpec;
import zip.hedlund.pureedgesim.edgesdk.model.LinkRef;
import zip.hedlund.pureedgesim.edgesdk.model.LinkSpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeDecl;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.NodeSpec;
import zip.hedlund.pureedgesim.edgesdk.model.Position;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.model.WorkloadSpec;
import zip.hedlund.pureedgesim.edgesdk.network.Degradation;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageWorkload;

/**
 * Fluent builder of a {@link Scenario}. Declarations are kept as given and
 * resolved by {@link #build()}, which validates everything and fails with a
 * clear message: unknown node, a channel an endpoint does not support, an
 * unconnected node, a duplicate name, a fault that targets nothing.
 */
public final class ScenarioBuilder {

	private record PendingNode(String name, NodeSpec spec, Layout layout) {
	}

	private interface LinkRule {
		List<LinkSpec> resolve(DesignTopology t);
	}

	final String name;
	double duration = 600;
	long seed = 1;
	double mapWidth = 1_000;
	double mapLength = 1_000;
	final List<Scenario.WorkloadAssignment> workloads = new ArrayList<>();
	StorageWorkload storageWorkload;
	FaultModel faultModel = NoFaults.INSTANCE;
	boolean emergentFaults = true;
	boolean volatileStorage;
	double sampleInterval = 10;
	double networkUpdateInterval = 0.02;
	private boolean allowIsolated;
	private final List<PendingNode> nodes = new ArrayList<>();
	private final List<LinkRule> linkRules = new ArrayList<>();
	private final Map<String, Select> groups = new LinkedHashMap<>();

	ScenarioBuilder(String name) {
		this.name = Objects.requireNonNull(name, "name");
	}

	ScenarioBuilder copy() {
		ScenarioBuilder b = new ScenarioBuilder(name);
		b.duration = duration;
		b.seed = seed;
		b.mapWidth = mapWidth;
		b.mapLength = mapLength;
		b.workloads.addAll(workloads);
		b.storageWorkload = storageWorkload;
		b.faultModel = faultModel;
		b.emergentFaults = emergentFaults;
		b.volatileStorage = volatileStorage;
		b.sampleInterval = sampleInterval;
		b.networkUpdateInterval = networkUpdateInterval;
		b.allowIsolated = allowIsolated;
		b.nodes.addAll(nodes);
		b.linkRules.addAll(linkRules);
		b.groups.putAll(groups);
		return b;
	}

	// ---- settings ------------------------------------------------------------------

	/** Simulated seconds (default 600). */
	public ScenarioBuilder duration(double seconds) {
		if (!(seconds > 0))
			throw new IllegalArgumentException("duration must be positive");
		this.duration = seconds;
		return this;
	}

	/** Base seed of layout, workload, faults and algorithms (default 1). */
	public ScenarioBuilder seed(long value) {
		this.seed = value;
		return this;
	}

	/** Map size in metres (default 1 000 x 1 000). */
	public ScenarioBuilder map(double width, double length) {
		if (!(width > 0) || !(length > 0))
			throw new IllegalArgumentException("map dimensions must be positive");
		this.mapWidth = width;
		this.mapLength = length;
		return this;
	}

	/** Seconds between two timeline samples (default 10). */
	public ScenarioBuilder sampleInterval(double seconds) {
		this.sampleInterval = seconds;
		return this;
	}

	/** Step of the transfer model, seconds (default 0.02). Smaller is more precise and slower. */
	public ScenarioBuilder networkUpdateInterval(double seconds) {
		this.networkUpdateInterval = seconds;
		return this;
	}

	/** Whether links go down when nodes move out of range (default true). */
	public ScenarioBuilder emergentFaults(boolean enabled) {
		this.emergentFaults = enabled;
		return this;
	}

	/** Whether a crash wipes the node's key-value store (default false: storage persists). */
	public ScenarioBuilder volatileStorage(boolean enabled) {
		this.volatileStorage = enabled;
		return this;
	}

	/** Accept nodes without any link. */
	public ScenarioBuilder allowIsolatedNodes() {
		this.allowIsolated = true;
		return this;
	}

	// ---- nodes ---------------------------------------------------------------------

	/** Adds a node of any tier. */
	public ScenarioBuilder addNode(String nodeName, NodeSpec spec, Layout layout) {
		nodes.add(new PendingNode(nodeName, spec.named(nodeName), layout));
		return this;
	}

	public ScenarioBuilder addNode(String nodeName, NodeSpec spec) {
		return addNode(nodeName, spec, Layout.random());
	}

	/** Adds {@code count} end devices named {@code <preset name>-0 …}, placed at random. */
	public ScenarioBuilder addEndDevices(NodeSpec preset, int count) {
		return addEndDevices(preset, count, Layout.random());
	}

	/** Adds {@code count} end devices named {@code <preset name>-0 …}. */
	public ScenarioBuilder addEndDevices(NodeSpec preset, int count, Layout layout) {
		requireTier(preset, Tier.END_DEVICE);
		int start = (int) nodes.stream().filter(n -> n.name.startsWith(preset.name() + "-")).count();
		for (int i = 0; i < count; i++)
			addNode(preset.name() + "-" + (start + i), preset, layout);
		return this;
	}

	public ScenarioBuilder addEndDevice(String nodeName, NodeSpec preset, Layout layout) {
		requireTier(preset, Tier.END_DEVICE);
		return addNode(nodeName, preset, layout);
	}

	public ScenarioBuilder addEdgeNode(String nodeName, NodeSpec preset) {
		return addEdgeNode(nodeName, preset, Layout.random());
	}

	public ScenarioBuilder addEdgeNode(String nodeName, NodeSpec preset, Layout layout) {
		requireTier(preset, Tier.EDGE_STATIC, Tier.EDGE_MOBILE);
		return addNode(nodeName, preset, layout);
	}

	public ScenarioBuilder addEdgeNodes(NodeSpec preset, int count, Layout layout) {
		requireTier(preset, Tier.EDGE_STATIC, Tier.EDGE_MOBILE);
		int start = (int) nodes.stream().filter(n -> n.name.startsWith(preset.name() + "-")).count();
		for (int i = 0; i < count; i++)
			addNode(preset.name() + "-" + (start + i), preset, layout);
		return this;
	}

	public ScenarioBuilder addCloud(String nodeName, NodeSpec preset) {
		return addCloud(nodeName, preset, Layout.random());
	}

	public ScenarioBuilder addCloud(String nodeName, NodeSpec preset, Layout layout) {
		requireTier(preset, Tier.CLOUD);
		return addNode(nodeName, preset, layout);
	}

	private static void requireTier(NodeSpec spec, Tier... tiers) {
		for (Tier t : tiers)
			if (spec.tier() == t)
				return;
		throw new IllegalArgumentException(spec.name() + " is a " + spec.tier() + " preset; expected one of "
				+ List.of(tiers) + " (use addNode for any tier)");
	}

	// ---- links and groups ----------------------------------------------------------

	/** Links two nodes over a channel; {@code a} is the side nearer to the devices. */
	public ScenarioBuilder connect(String a, String b, ChannelSpec channel) {
		linkRules.add(t -> {
			for (String n : List.of(a, b))
				if (!t.contains(n))
					throw new IllegalArgumentException("connect(" + a + ", " + b + "): unknown node \"" + n + "\"");
			return List.of(new LinkSpec(NodeRef.of(a), NodeRef.of(b), channel));
		});
		return this;
	}

	/** Links every selected node of {@code a} to every selected node of {@code b}. */
	public ScenarioBuilder connect(Select a, Select b, ChannelSpec channel) {
		linkRules.add(t -> {
			List<LinkSpec> out = new ArrayList<>();
			Set<NodeRef> sb = b.resolve(t);
			for (NodeRef x : a.resolve(t))
				for (NodeRef y : sb)
					if (!x.equals(y))
						out.add(new LinkSpec(x, y, channel));
			return out;
		});
		return this;
	}

	/** Starts a "connect each node of a tier to its nearest node of another tier" rule. */
	public NearestRule connectEach(Tier from) {
		return new NearestRule(Select.tier(from));
	}

	/** Starts a "connect each selected node to its nearest node of a tier" rule. */
	public NearestRule connectEach(Select from) {
		return new NearestRule(from);
	}

	/** Second step of {@link #connectEach}. */
	public final class NearestRule {
		private final Select from;
		private Select to;

		private NearestRule(Select from) {
			this.from = from;
		}

		/** The candidates: the nodes of these tiers. */
		public NearestRule toNearest(Tier... tiers) {
			this.to = Select.tier(tiers);
			return this;
		}

		/** The candidates: the selected nodes. */
		public NearestRule toNearest(Select candidates) {
			this.to = candidates;
			return this;
		}

		/** Links each node to its nearest candidate (initial positions) over the channel. */
		public ScenarioBuilder via(ChannelSpec channel) {
			Select target = Objects.requireNonNull(to, "call toNearest(...) first");
			linkRules.add(t -> {
				List<LinkSpec> out = new ArrayList<>();
				Set<NodeRef> candidates = target.resolve(t);
				for (NodeRef x : from.resolve(t)) {
					Position px = t.node(x).position();
					candidates.stream().filter(c -> !c.equals(x))
							.min((p, q) -> Double.compare(px.distanceTo(t.node(p).position()),
									px.distanceTo(t.node(q).position())))
							.ifPresent(c -> out.add(new LinkSpec(x, c, channel)));
				}
				return out;
			});
			return ScenarioBuilder.this;
		}
	}

	/**
	 * Declares a named group of nodes (a region, a swarm…). Groups are resolved
	 * before the links, so select members by name, pattern or tier, not by their
	 * links (link rules may themselves use groups).
	 */
	public ScenarioBuilder group(String groupName, Select members) {
		if (groups.containsKey(groupName))
			throw new IllegalArgumentException("duplicate group \"" + groupName + "\"");
		groups.put(groupName, members);
		return this;
	}

	// ---- workloads and faults ------------------------------------------------------

	/** Every end device generates this workload. */
	public ScenarioBuilder withWorkload(WorkloadSpec workload) {
		return withWorkload(workload, Select.tier(Tier.END_DEVICE));
	}

	/** The selected nodes generate this workload. */
	public ScenarioBuilder withWorkload(WorkloadSpec workload, Select devices) {
		workloads.add(new Scenario.WorkloadAssignment(workload, devices));
		return this;
	}

	/** Clients issue reads and writes, handled by the candidate's storage strategy. */
	public ScenarioBuilder withStorageWorkload(StorageWorkload workload) {
		this.storageWorkload = workload;
		return this;
	}

	/** The fault model (default {@link NoFaults}). Compose models with {@link FaultModel#and}. */
	public ScenarioBuilder withFaults(FaultModel model) {
		this.faultModel = Objects.requireNonNull(model, "model");
		return this;
	}

	// ---- build ---------------------------------------------------------------------

	/** Resolves and validates the declarations. */
	public Scenario build() {
		if (nodes.isEmpty())
			throw new IllegalArgumentException(name + ": a scenario needs nodes");
		Set<String> names = new HashSet<>();
		for (PendingNode n : nodes) {
			if (!n.name.matches("[A-Za-z0-9_.\\-]+"))
				throw new IllegalArgumentException("invalid node name \"" + n.name
						+ "\": use letters, digits, '.', '_' and '-'");
			if (!names.add(n.name))
				throw new IllegalArgumentException("duplicate node name \"" + n.name + "\"");
		}
		List<NodeDecl> decls = place();
		DesignTopology bare = new DesignTopology(decls, List.of(), Map.of());

		Map<String, GroupSpec> groupSpecs = new LinkedHashMap<>();
		for (Map.Entry<String, Select> g : groups.entrySet()) {
			if (names.contains(g.getKey()))
				throw new IllegalArgumentException("group \"" + g.getKey() + "\" has the name of a node");
			Set<String> members = new LinkedHashSet<>();
			g.getValue().resolve(bare).forEach(r -> members.add(r.name()));
			if (members.isEmpty())
				throw new IllegalArgumentException("group \"" + g.getKey() + "\" selects no node");
			groupSpecs.put(g.getKey(), new GroupSpec(g.getKey(), members));
		}
		DesignTopology withGroups = new DesignTopology(decls, List.of(), groupSpecs);

		Map<LinkRef, LinkSpec> links = new LinkedHashMap<>();
		for (LinkRule rule : linkRules)
			for (LinkSpec l : rule.resolve(withGroups)) {
				LinkRef reverse = new LinkRef(l.b().name(), l.a().name(), l.channel().name());
				if (links.containsKey(l.ref()) || links.containsKey(reverse))
					continue;
				for (NodeRef end : List.of(l.a(), l.b()))
					if (!withGroups.node(end).spec().supports(l.channel()))
						throw new IllegalArgumentException("cannot link " + l.a() + " and " + l.b() + " over "
								+ l.channel().name() + ": " + end + " has no " + l.channel().name()
								+ " interface (add it with withChannel)");
				links.put(l.ref(), l);
			}
		DesignTopology design = new DesignTopology(decls, List.copyOf(links.values()), groupSpecs);

		if (!allowIsolated)
			for (NodeDecl d : decls)
				if (design.linksOf(d.ref()).isEmpty())
					throw new IllegalArgumentException("node \"" + d.name()
							+ "\" has no link; connect it or call allowIsolatedNodes()");
		for (Scenario.WorkloadAssignment w : workloads)
			if (w.devices().resolve(design).isEmpty())
				throw new IllegalArgumentException("workload \"" + w.workload().name() + "\" selects no device");
		if (storageWorkload != null) {
			if (storageWorkload.hosts().resolve(design).isEmpty())
				throw new IllegalArgumentException("the storage workload selects no host");
			if (storageWorkload.clients().resolve(design).isEmpty())
				throw new IllegalArgumentException("the storage workload selects no client");
		}
		faultModel.install(new Validator(design, duration), design, new Random(seed));
		return new Scenario(this, design);
	}

	private List<NodeDecl> place() {
		Random r = new Random(seed ^ 0x5CE9A12EL);
		Map<String, Position> placed = new HashMap<>();
		// fixed and random first, in declaration order
		for (PendingNode n : nodes) {
			if (n.layout instanceof Layout.At at)
				placed.put(n.name, new Position(at.x(), at.y()));
			else if (n.layout instanceof Layout.Anywhere)
				placed.put(n.name, new Position(r.nextDouble() * mapWidth, r.nextDouble() * mapLength));
		}
		// then nodes placed near others, until nothing changes
		boolean progress = true;
		while (progress) {
			progress = false;
			for (PendingNode n : nodes) {
				if (placed.containsKey(n.name) || !(n.layout instanceof Layout.Near near))
					continue;
				Position anchor = placed.get(near.anchor());
				if (anchor == null)
					continue;
				double angle = r.nextDouble() * 2 * Math.PI;
				double dist = near.radius() * Math.sqrt(r.nextDouble());
				double x = Math.min(mapWidth, Math.max(0, anchor.x() + Math.cos(angle) * dist));
				double y = Math.min(mapLength, Math.max(0, anchor.y() + Math.sin(angle) * dist));
				placed.put(n.name, new Position(x, y));
				progress = true;
			}
		}
		List<NodeDecl> out = new ArrayList<>();
		for (PendingNode n : nodes) {
			Position p = placed.get(n.name);
			if (p == null)
				throw new IllegalArgumentException("node \"" + n.name + "\" is placed near \""
						+ ((Layout.Near) n.layout).anchor() + "\", which does not exist");
			out.add(new NodeDecl(NodeRef.of(n.name), n.spec, p));
		}
		return out;
	}

	/** Dry run of the fault model: every target must exist. */
	private static final class Validator implements FaultScheduler {
		private final DesignTopology t;
		private final double duration;

		Validator(DesignTopology t, double duration) {
			this.t = t;
			this.duration = duration;
		}

		private void links(LinkSelect s, String what) {
			if (s.resolve(t).isEmpty())
				throw new IllegalArgumentException("fault " + what + " targets no link");
		}

		private void nodes(Select s, String what) {
			if (s.resolve(t).isEmpty())
				throw new IllegalArgumentException("fault " + what + " targets no node");
		}

		@Override
		public void linkDown(LinkSelect target, double at, double d) {
			links(target, "linkDown at " + at + "s");
		}

		@Override
		public void degrade(LinkSelect target, double at, double d, Degradation degradation) {
			links(target, "degrade at " + at + "s");
		}

		@Override
		public void crash(Select target, double at, Recovery recovery) {
			nodes(target, "crash at " + at + "s");
		}

		@Override
		public void partition(List<Select> groups, double at, double d) {
			for (Select g : groups)
				nodes(g, "partition at " + at + "s (a group)");
		}

		@Override
		public void at(double time, Runnable action) {
			// custom actions run only in real runs
		}

		@Override
		public double now() {
			return 0;
		}

		@Override
		public double duration() {
			return duration;
		}
	}
}
