package zip.hedlund.pureedgesim.edgesdk.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;

import zip.hedlund.pureedgesim.edgesdk.algorithm.Algorithm;
import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultInjector;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultLog;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultType;
import zip.hedlund.pureedgesim.edgesdk.metrics.DeclarationRecord;
import zip.hedlund.pureedgesim.edgesdk.metrics.NodeEnergy;
import zip.hedlund.pureedgesim.edgesdk.metrics.RunData;
import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.network.DownCause;
import zip.hedlund.pureedgesim.edgesdk.network.LiveNetwork;
import zip.hedlund.pureedgesim.edgesdk.network.LossReason;
import zip.hedlund.pureedgesim.edgesdk.network.NetworkEngine;
import zip.hedlund.pureedgesim.edgesdk.network.RangeMonitor;
import zip.hedlund.pureedgesim.edgesdk.network.SdkLink;
import zip.hedlund.pureedgesim.edgesdk.network.Transfer;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageStrategy;

/**
 * Everything of one run: nodes, live network, fault injection, the candidate's
 * algorithms, the task and storage engines, and the recorders. Created by
 * {@link SdkSimulationManager} from the {@link RunContext}.
 */
public final class SdkWorld {

	private final RunContext context;
	private final RunSpec spec;
	private final Scenario scenario;
	private final DesignTopology design;
	private final SdkSimulationManager manager;
	private final Scheduler scheduler;
	private final Map<String, SdkNode> nodes = new LinkedHashMap<>();
	private final LiveNetwork live = new LiveNetwork();
	private final NetworkEngine network;
	private final FaultLog faultLog = new FaultLog();
	private final List<DeclarationRecord> declarations = new ArrayList<>();
	private final CustomMetrics custom = new CustomMetrics();
	private final List<AlgorithmRuntime> runtimes = new ArrayList<>();
	private final Map<SdkNode, Integer> batteryFaults = new LinkedHashMap<>();
	private TaskEngine tasks;
	private StorageEngine storage;
	private Sampler sampler;
	private RangeMonitor range;

	SdkWorld(SdkSimulationManager manager, RunContext context) {
		this.manager = manager;
		this.context = context;
		this.spec = context.spec();
		this.scenario = spec.scenario();
		this.design = scenario.design();
		this.scheduler = new Scheduler(manager.getSimulation(), this);
		this.network = new NetworkEngine(this, live, new Random(spec.algorithmSeed() ^ 0x5DEECE66DL));
	}

	// ---- accessors ---------------------------------------------------------------

	public RunSpec spec() {
		return spec;
	}

	public Scenario scenario() {
		return scenario;
	}

	public DesignTopology design() {
		return design;
	}

	public LiveNetwork live() {
		return live;
	}

	public NetworkEngine network() {
		return network;
	}

	public FaultLog faultLog() {
		return faultLog;
	}

	public SdkSimulationManager manager() {
		return manager;
	}

	public double now() {
		return manager.getSimulation().clock();
	}

	public double duration() {
		return scenario.duration();
	}

	public SdkNode node(NodeRef ref) {
		return node(ref.name());
	}

	public SdkNode node(String name) {
		SdkNode n = nodes.get(name);
		if (n == null)
			throw new IllegalArgumentException("unknown node \"" + name + "\"");
		return n;
	}

	public List<SdkNode> nodes() {
		return List.copyOf(nodes.values());
	}

	TaskEngine tasks() {
		return tasks;
	}

	StorageEngine storage() {
		return storage;
	}

	CustomMetrics custom() {
		return custom;
	}

	void register(SdkNode node) {
		nodes.put(node.decl().name(), node);
		live.addNode(node);
	}

	// ---- scheduling ----------------------------------------------------------------

	/** Runs an action after a delay. */
	public Scheduler.Action after(double delay, Runnable action) {
		return scheduler.after(delay, action);
	}

	/** Runs an action at an absolute time (now if it is in the past). */
	public Scheduler.Action at(double time, Runnable action) {
		return scheduler.after(Math.max(0, time - now()), action);
	}

	/** Runs SDK or developer code; the first exception ends the run and is reported. */
	public void guard(Runnable action) {
		if (context.failure() != null)
			return;
		try {
			action.run();
		} catch (RuntimeException | Error e) {
			context.fail(e);
			manager.getSimulation().terminate();
		}
	}

	// ---- life cycle ------------------------------------------------------------------

	/** Time 0: faults, algorithms, workloads and recorders start. */
	void start() {
		range = new RangeMonitor(live, faultLog);
		if (scenario.emergentFaults()) {
			range.check(0);
			double dt = SimulationParameters.updateInterval;
			// just after the nodes have moved
			repeat(dt + 1e-6, dt, () -> range.check(now()));
		}

		Random faultRng = new Random(spec.faultSeed());
		scenario.faultModel().install(new FaultInjector(this, scenario.faultModel().label()), design, faultRng);

		OrchestrationPolicy policy = spec.candidate().orchestration().get();
		AlgorithmRuntime orch = new AlgorithmRuntime(this, policy, Transfer.Kind.CONTROL,
				new Random(spec.algorithmSeed()));
		runtimes.add(orch);
		tasks = new TaskEngine(this, orch, policy);

		AlgorithmRuntime store = null;
		StorageStrategy strategy = null;
		if (scenario.storageWorkload().isPresent()) {
			strategy = spec.candidate().storage().get();
			store = new AlgorithmRuntime(this, strategy, Transfer.Kind.STORAGE,
					new Random(spec.algorithmSeed() * 31 + 7));
			runtimes.add(store);
			storage = new StorageEngine(this, store, strategy, scenario.storageWorkload().get());
			strategy.bindStorage(storage.ops());
		}

		sampler = new Sampler(this);
		for (AlgorithmRuntime r : runtimes)
			r.start();
		tasks.start();
		if (storage != null)
			storage.start();
		sampler.start(scenario.sampleInterval());
	}

	private void repeat(double first, double period, Runnable action) {
		after(first, () -> {
			action.run();
			if (now() + period <= duration())
				repeat(period, period, action);
		});
	}

	/** End of run: collects the {@link RunData}. */
	void finish() {
		if (sampler == null) {
			context.complete(null);
			return;
		}
		sampler.sample();
		for (AlgorithmRuntime r : runtimes)
			guard(r::stop);
		List<NodeEnergy> energy = new ArrayList<>();
		for (SdkNode n : nodes.values()) {
			var e = n.sdkEnergy();
			energy.add(new NodeEnergy(n.decl().name(), n.decl().tier().name(), e.getCpuEnergyConsumption(),
					e.networkWh(), e.isBatteryPowered() ? e.getBatteryLevelPercentage() : Double.NaN,
					n.isPermanentlyDown() && batteryFaults.containsKey(n)));
		}
		Map<String, Long> lost = new LinkedHashMap<>();
		for (Map.Entry<LossReason, Long> e : network.lostByReason().entrySet())
			lost.put(e.getKey().name(), e.getValue());
		RunData data = new RunData(spec.runId(), scenario.name(), spec.candidate().name(), spec.condition(),
				spec.params(), spec.replication(), spec.workloadSeed(), spec.faultSeed(), spec.algorithmSeed(),
				duration(), tasks.records(), storage == null ? List.of() : storage.records(), faultLog, declarations,
				sampler.samples(), energy, network.bytesSent(Transfer.Kind.CONTROL),
				network.bytesSent(Transfer.Kind.STORAGE),
				network.bytesSent(Transfer.Kind.TASK_INPUT) + network.bytesSent(Transfer.Kind.TASK_RESULT), lost,
				custom.snapshot(), nodes.size());
		context.complete(data);
	}

	// ---- faults on nodes -----------------------------------------------------------

	/** Crashes a node (one hold); the engines and algorithms lose its state. */
	public void crash(SdkNode node, boolean permanent) {
		if (!node.crash(permanent))
			return;
		for (SdkLink l : live.linksOf(node))
			l.addCause(DownCause.ENDPOINT_CRASHED);
		live.changed();
		for (AlgorithmRuntime r : runtimes)
			r.onNodeCrashed(node);
		if (tasks != null)
			tasks.onNodeCrashed(node);
		if (storage != null)
			storage.onNodeCrashed(node);
	}

	/** Releases one crash hold; when none is left the node restarts. */
	public void recover(SdkNode node) {
		if (!node.recover())
			return;
		for (SdkLink l : live.linksOf(node))
			l.removeCause(DownCause.ENDPOINT_CRASHED);
		live.changed();
		for (AlgorithmRuntime r : runtimes)
			r.onNodeRestarted(node);
	}

	/** A battery ran out: the node stops for good. */
	void onBatteryDeath(SdkNode node) {
		if (batteryFaults.containsKey(node))
			return;
		batteryFaults.put(node, faultLog.open(FaultType.CRASH_STOP, node.decl().name(), "battery", now(),
				List.of(node.decl().name()), ""));
		// the node is already dead in PureEdgeSim; mirror it in the SDK
		node.crash(true);
		for (SdkLink l : live.linksOf(node))
			l.addCause(DownCause.ENDPOINT_CRASHED);
		live.changed();
		for (AlgorithmRuntime r : runtimes)
			r.onNodeCrashed(node);
		if (tasks != null)
			tasks.onNodeCrashed(node);
		if (storage != null)
			storage.onNodeCrashed(node);
	}

	// ---- declarations ----------------------------------------------------------------

	void declare(NodeRef by, NodeRef target, boolean suspected) {
		boolean reachable = live.reachable(node(by), node(target));
		declarations.add(new DeclarationRecord(now(), by.name(), target.name(), suspected,
				suspected != reachable));
	}

	/** @return the algorithms of the run */
	List<Algorithm> algorithms() {
		return runtimes.stream().map(AlgorithmRuntime::algorithm).toList();
	}
}
