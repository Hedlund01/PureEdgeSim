package zip.hedlund.pureedgesim.edgesdk.algorithm.policies;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import zip.hedlund.pureedgesim.edgesdk.algorithm.LocalView;
import zip.hedlund.pureedgesim.edgesdk.algorithm.NodeLocal;
import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.EnergySpec;
import zip.hedlund.pureedgesim.edgesdk.model.LinkSpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeDecl;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Position;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;

/**
 * MVTORA, the MEC-VFC-aided task offloading and resource allocation approach of
 * Sun et al., "Joint Task Offloading and Resource Allocation in
 * Aerial-Terrestrial UAV Networks with Edge and Fog Computing for Post-Disaster
 * Rescue" (IEEE TMC, arXiv:2309.16709).
 * <p>
 * <b>Roles.</b> The end devices that create tasks are the client UAVs
 * (C-UAVs). Each one is served by the MEC server (the edge UAV, E-UAV) with the
 * lowest design latency among {@code mecServers}. The {@code vehicles} are the
 * vehicle fog nodes: a device can use a vehicle it has a direct wireless design
 * link to while the vehicle is within that link's range.
 * <p>
 * <b>Information.</b> As in the paper, the E-UAV collects the state of the
 * vehicles and hands it to the C-UAVs. Every {@link Params#slot() slot}, each
 * vehicle sends its position and load to the MEC servers, and each MEC server
 * sends its devices the vehicle states and the MEC tasks it is serving. A device
 * that decides to offload tells its server, which counts the task in the next
 * slot. State older than {@link Params#staleSlots()} slots is ignored, so a
 * device that stops hearing from its server computes locally.
 * <p>
 * <b>Decision</b> (Algorithm 5, then one step of Algorithm 1). For each new
 * task the device computes the utility of local computing; the utility of
 * VFC-assisted offloading, choosing the vehicles with Algorithm 3 and dividing
 * the task among them with the genetic algorithm of Algorithm 4; and keeps the
 * better of the two. It then tries MEC-assisted offloading: Algorithm 2 shares
 * the server's MIPS between the MEC tasks it serves and this one, and the device
 * switches to MEC when that utility is higher. Tasks arrive one at a time here,
 * not in slots, so each arrival is one better-response update of the
 * offloading game against the tasks already in flight.
 * <p>
 * <b>Model mapping.</b> ηₙDₙ is the task length (MI) and computing resources
 * are MIPS. Rates come from the design channels (the bottleneck of the design
 * path to the server, the direct link to a vehicle), and delays include the
 * result's return and the channel latency. Energy follows the SDK model rather
 * than {@code k f³}: a busy core draws {@code (maxW − idleW) / cores}, and the
 * device pays half of {@code joulesPerBit} for the bits it sends and receives.
 * The allocation Fₙ is the server's share of MIPS assumed by the utility; the
 * simulator itself runs a task on one core, so Fₙ is capped at one core.
 * <p>
 * <b>Benchmarks.</b> Restricting the {@link Mode modes} gives the paper's
 * baselines: {@code LOCAL} alone is ELC, {@code MEC} alone EMC, {@code LOCAL}
 * and {@code VFC} VTO, {@code LOCAL} and {@code MEC} MTO. A device with no
 * allowed option (e.g. MEC only, but no news from its server) computes locally.
 * <p>
 * The algorithm publishes {@code mvtora.local}, {@code mvtora.mec},
 * {@code mvtora.vfc} and {@code mvtora.infeasible} (decision counters) and
 * {@code mvtora.utility} (the predicted utility of each decision).
 */
public class Mvtora extends OrchestrationPolicy {

	/**
	 * Parameters of MVTORA, by default the values of the paper's Table 1 where
	 * they apply.
	 *
	 * @param alpha        αₙ, weight of the completion delay
	 * @param energyWeight βₙ, weight of the energy, per joule
	 * @param beta         β, the constant that keeps the delay revenue
	 *                     non-negative (not given in the paper)
	 * @param pricePerMips ρ₀, unit price of MEC resources per MIPS (0.001 per GHz)
	 * @param maxVehicles  Kₙ, the most vehicles a task is divided among
	 * @param slot         Δt, seconds between state reports
	 * @param staleSlots   state older than this many slots is ignored
	 * @param generations  G, generations of the genetic algorithm
	 * @param population   L, its population size
	 * @param crossover    pc, its crossover probability
	 * @param mutation     pm, its mutation probability
	 * @param accuracy     ε, relative accuracy of the bisection on γ
	 */
	public record Params(double alpha, double energyWeight, double beta, double pricePerMips, int maxVehicles,
			double slot, int staleSlots, int generations, int population, double crossover, double mutation,
			double accuracy) {

		public Params {
			if (!(alpha >= 0) || !(energyWeight >= 0) || !(beta > 0) || !(pricePerMips >= 0))
				throw new IllegalArgumentException("need alpha, energyWeight, pricePerMips >= 0 and beta > 0");
			if (maxVehicles < 1 || !(slot > 0) || staleSlots < 1 || generations < 0 || population < 2)
				throw new IllegalArgumentException(
						"need maxVehicles >= 1, slot > 0, staleSlots >= 1, generations >= 0 and population >= 2");
			if (crossover < 0 || crossover > 1 || mutation < 0 || mutation > 1 || !(accuracy > 0))
				throw new IllegalArgumentException("need crossover and mutation in [0, 1] and accuracy > 0");
		}

		/** The paper's values: α 0.9, βₙ 0.1, ρ₀ 0.001/GHz, Kₙ 5, Δt 1 s, G 200, L 50, pc 0.8, pm 0.1. */
		public static Params defaults() {
			return new Params(0.9, 0.1, 1.0, 1e-6, 5, 1.0, 3, 200, 50, 0.8, 0.1, 1e-6);
		}

		public Params withMaxVehicles(int k) {
			return new Params(alpha, energyWeight, beta, pricePerMips, k, slot, staleSlots, generations, population,
					crossover, mutation, accuracy);
		}

		public Params withSlot(double seconds) {
			return new Params(alpha, energyWeight, beta, pricePerMips, maxVehicles, seconds, staleSlots, generations,
					population, crossover, mutation, accuracy);
		}

		public Params withWeights(double alpha, double energyWeight) {
			return new Params(alpha, energyWeight, beta, pricePerMips, maxVehicles, slot, staleSlots, generations,
					population, crossover, mutation, accuracy);
		}

		public Params withPricePerMips(double price) {
			return new Params(alpha, energyWeight, beta, price, maxVehicles, slot, staleSlots, generations,
					population, crossover, mutation, accuracy);
		}

		public Params withGenetic(int generations, int population, double crossover, double mutation) {
			return new Params(alpha, energyWeight, beta, pricePerMips, maxVehicles, slot, staleSlots, generations,
					population, crossover, mutation, accuracy);
		}
	}

	/** The offloading strategies of a C-UAV. */
	public enum Mode {
		/** Local computing. */
		LOCAL,
		/** MEC-assisted offloading to the E-UAV. */
		MEC,
		/** VFC-assisted offloading, divided among vehicles. */
		VFC
	}

	/** What a vehicle reports about itself. */
	record VehicleState(NodeRef vehicle, Position position, int cores, double mipsPerCore, int load, double at) {
	}

	/**
	 * A task, or a part of one, that the server counts as running on itself or on
	 * a vehicle, from {@code since} (the decision) to {@code until} (its predicted
	 * completion). {@code headroom} is {@code β + Tmax − (transfer time)} for MEC.
	 */
	record Use(long task, NodeRef node, double work, double headroom, double since, double until) {
	}

	/** What a server tells its devices every slot. */
	record SlotInfo(double at, List<VehicleState> vehicles, List<Use> uses) {
	}

	/** A device's decision, sent to its server. */
	record Offload(List<Use> uses) {
	}

	private static final long REPORT_BYTES = 64;
	private static final long HEADER_BYTES = 64;
	private static final long ENTRY_BYTES = 40;

	private final Params params;
	private final Select mecSelect;
	private final Select vehicleSelect;
	private final Set<Mode> modes;

	private Set<NodeRef> servers;
	private Set<NodeRef> vehicles;
	private final Map<NodeRef, NodeRef> serverOf = new TreeMap<>();
	private final Map<NodeRef, List<NodeRef>> devicesOf = new TreeMap<>();
	private NodeLocal<Map<NodeRef, VehicleState>> vehicleStates;
	private NodeLocal<Set<Use>> serving;
	private NodeLocal<DeviceState> deviceStates;

	/** The device's latest slot information and the decisions it made since. */
	private static final class DeviceState {
		SlotInfo latest;
		final List<Use> sent = new ArrayList<>();
	}

	/** The paper's parameters; MEC servers are the static edge nodes and vehicles the mobile ones. */
	public Mvtora() {
		this(Params.defaults(), Select.tier(Tier.EDGE_STATIC), Select.tier(Tier.EDGE_MOBILE));
	}

	/**
	 * @param params     the parameters
	 * @param mecServers the MEC servers (E-UAVs)
	 * @param vehicles   the vehicle fog nodes
	 */
	public Mvtora(Params params, Select mecServers, Select vehicles) {
		this(params, mecServers, vehicles, EnumSet.allOf(Mode.class));
	}

	/**
	 * @param params     the parameters
	 * @param mecServers the MEC servers (E-UAVs)
	 * @param vehicles   the vehicle fog nodes
	 * @param modes      the strategies a C-UAV may choose from
	 */
	public Mvtora(Params params, Select mecServers, Select vehicles, Set<Mode> modes) {
		if (modes.isEmpty())
			throw new IllegalArgumentException("need at least one mode");
		this.params = params;
		this.mecSelect = mecServers;
		this.vehicleSelect = vehicles;
		this.modes = EnumSet.copyOf(modes);
	}

	@Override
	public void onStart(SdkContext ctx) {
		DesignTopology t = ctx.topology();
		servers = mecSelect.resolve(t);
		vehicles = vehicleSelect.except(mecSelect).resolve(t);
		vehicleStates = ctx.nodeLocal(n -> new HashMap<>());
		serving = ctx.nodeLocal(n -> new LinkedHashSet<>());
		deviceStates = ctx.nodeLocal(n -> new DeviceState());
		for (NodeRef device : t.nodes(Tier.END_DEVICE)) {
			if (vehicles.contains(device))
				continue;
			t.nearest(device, servers).ifPresent(s -> {
				serverOf.put(device, s);
				devicesOf.computeIfAbsent(s, k -> new ArrayList<>()).add(device);
			});
		}
		for (NodeRef v : vehicles)
			startReporting(v);
		for (NodeRef s : servers)
			startServing(s);
	}

	@Override
	public void onNodeRestarted(NodeRef at) {
		if (vehicles.contains(at))
			startReporting(at);
		if (servers.contains(at))
			startServing(at);
	}

	private void startReporting(NodeRef vehicle) {
		ctx.schedule(vehicle, 0, () -> report(vehicle));
		ctx.every(vehicle, params.slot(), () -> report(vehicle));
	}

	private void report(NodeRef vehicle) {
		LocalView me = ctx.local(vehicle);
		double perCore = ctx.topology().node(vehicle).spec().compute().mipsPerCore();
		VehicleState state = new VehicleState(vehicle, me.position(), me.cores(), perCore,
				me.busyCores() + me.queuedTasks(), ctx.now());
		for (NodeRef s : servers)
			ctx.send(vehicle, s, REPORT_BYTES, state);
	}

	private void startServing(NodeRef server) {
		// half a slot after the vehicles, so that their reports have arrived
		ctx.schedule(server, params.slot() / 2, () -> {
			broadcast(server);
			ctx.every(server, params.slot(), () -> broadcast(server));
		});
	}

	private void broadcast(NodeRef server) {
		double now = ctx.now();
		double stale = params.slot() * params.staleSlots();
		Set<Use> uses = serving.at(server);
		// A MEC task ends at its predicted completion, unless the server still holds
		// more tasks than that leaves (a queue the prediction missed): then the
		// latest-ending ones are kept for another slot.
		LocalView me = ctx.local(server);
		int held = me.busyCores() + me.queuedTasks();
		List<Use> mine = new ArrayList<>();
		for (Use u : uses)
			if (u.node().equals(server))
				mine.add(u);
		mine.sort(Comparator.comparingDouble(Use::until).reversed());
		uses.removeIf(u -> u.until() <= now);
		for (int i = 0; i < Math.min(held, mine.size()); i++) {
			Use u = mine.get(i);
			if (u.until() <= now)
				uses.add(new Use(u.task(), u.node(), u.work(), u.headroom(), u.since(), now + params.slot()));
		}
		List<VehicleState> fresh = new ArrayList<>();
		for (VehicleState v : vehicleStates.at(server).values())
			if (now - v.at() <= stale)
				fresh.add(v);
		SlotInfo info = new SlotInfo(now, List.copyOf(fresh), List.copyOf(uses));
		long bytes = HEADER_BYTES + ENTRY_BYTES * (fresh.size() + uses.size());
		for (NodeRef device : devicesOf.getOrDefault(server, List.of()))
			ctx.send(server, device, bytes, info);
	}

	@Override
	public void onMessage(NodeRef at, NodeRef from, Object payload) {
		if (payload instanceof VehicleState v)
			vehicleStates.at(at).put(v.vehicle(), v);
		else if (payload instanceof Offload o)
			serving.at(at).addAll(o.uses());
		else if (payload instanceof SlotInfo info) {
			DeviceState d = deviceStates.at(at);
			if (d.latest == null || info.at() > d.latest.at()) {
				d.latest = info;
				d.sent.removeIf(u -> u.until() <= info.at());
			}
		}
	}

	@Override
	public Placement place(NodeRef device, TaskRequest task) {
		double now = ctx.now();
		double tMax = task.deadline() - now;
		DeviceState d = deviceStates.at(device);
		SlotInfo info = d.latest != null && now - d.latest.at() <= params.slot() * params.staleSlots() ? d.latest
				: null;
		List<Use> uses = new ArrayList<>();
		if (info != null) {
			uses.addAll(info.uses());
			for (Use u : d.sent)
				if (!info.uses().contains(u))
					uses.add(u);
			uses.removeIf(u -> u.until() <= now);
		}

		Option local = modes.contains(Mode.LOCAL) ? local(device, task, tMax) : null;
		Option vfc = modes.contains(Mode.VFC) && info != null ? vfc(device, task, tMax, info, uses) : null;
		Option mec = modes.contains(Mode.MEC) && info != null ? mec(device, task, tMax, uses) : null;

		// Algorithm 5: the better of local and VFC; Algorithm 1: switch to MEC if it improves
		Option chosen = null;
		for (Option o : new Option[] { local, vfc, mec })
			if (o != null && (chosen == null || o.utility > chosen.utility))
				chosen = o;
		if (chosen == null)
			chosen = local(device, task, tMax);
		if (chosen.utility == Double.NEGATIVE_INFINITY) {
			ctx.metrics().add("mvtora.infeasible", 1);
			for (Option o : new Option[] { local, vfc, mec })
				if (o != null && o.delay < chosen.delay)
					chosen = o;
		} else
			ctx.metrics().record("mvtora.utility", chosen.utility);
		ctx.metrics().add("mvtora." + chosen.kind, 1);

		if (!chosen.uses.isEmpty()) {
			NodeRef server = serverOf.get(device);
			d.sent.addAll(chosen.uses);
			ctx.send(device, server, HEADER_BYTES + ENTRY_BYTES * chosen.uses.size(), new Offload(chosen.uses));
		}
		return chosen.placement;
	}

	/** One offloading strategy and its predicted outcome. */
	private record Option(String kind, Placement placement, double utility, double delay, List<Use> uses) {
	}

	private Option local(NodeRef device, TaskRequest task, double tMax) {
		LocalView me = ctx.local(device);
		NodeDecl decl = ctx.topology().node(device);
		double mips = effectiveMips(me.cores(), decl.spec().compute().mipsPerCore(),
				me.busyCores() + me.queuedTasks());
		double delay = task.lengthMi() / mips;
		double joules = corePowerW(decl.spec().energy(), me.cores()) * delay;
		return new Option("local", Placement.local(), MvtoraSolver.utility(params, tMax, delay, joules, 0), delay,
				List.of());
	}

	private Option vfc(NodeRef device, TaskRequest task, double tMax, SlotInfo info, List<Use> uses) {
		Position here = ctx.local(device).position();
		List<VehicleState> candidates = new ArrayList<>();
		List<LinkSpec> links = new ArrayList<>();
		for (VehicleState v : info.vehicles()) {
			if (v.vehicle().equals(device))
				continue;
			LinkSpec best = null;
			for (LinkSpec l : ctx.topology().linksOf(device))
				if (l.other(device).equals(v.vehicle()) && here.distanceTo(v.position()) <= l.channel().rangeM()
						&& (best == null || rate(l, device) > rate(best, device)))
					best = l;
			if (best != null) {
				candidates.add(v);
				links.add(best);
			}
		}
		if (candidates.isEmpty())
			return null;
		// Algorithm 3 on the preference value Pr(n, j) of Eq. 34
		int n = candidates.size();
		double[] perShare = new double[n];
		double[] fixed = new double[n];
		double[] joulesPerShare = new double[n];
		for (int j = 0; j < n; j++) {
			VehicleState v = candidates.get(j);
			LinkSpec l = links.get(j);
			// the report counts what ran then; add the parts placed since
			int load = v.load();
			for (Use u : uses)
				if (u.node().equals(v.vehicle()) && u.since() > v.at())
					load++;
			double mips = effectiveMips(v.cores(), v.mipsPerCore(), load);
			perShare[j] = task.inputBytes() * 8 / rate(l, device) + task.outputBytes() * 8 / rate(l, v.vehicle())
					+ task.lengthMi() / mips;
			fixed[j] = 2 * ctx.topology().designLatency(l);
			joulesPerShare[j] = deviceJoules(l, task);
		}
		double[] preference = new double[n];
		for (int j = 0; j < n; j++)
			preference[j] = perShare[j] + fixed[j];
		int[] picked = MvtoraSolver.selectVehicles(preference, params.maxVehicles());
		double[] ps = new double[picked.length];
		double[] fx = new double[picked.length];
		double[] jp = new double[picked.length];
		for (int i = 0; i < picked.length; i++) {
			ps[i] = perShare[picked[i]];
			fx[i] = fixed[picked[i]];
			jp[i] = joulesPerShare[picked[i]];
		}
		// Algorithm 4
		double[] lambda = MvtoraSolver.divide(params, tMax, ps, fx, jp, ctx.rng());
		double delay = MvtoraSolver.divisionDelay(lambda, ps, fx);
		double joules = 0;
		Map<NodeRef, Double> shares = new LinkedHashMap<>();
		List<Use> used = new ArrayList<>();
		for (int i = 0; i < picked.length; i++) {
			if (lambda[i] <= 0)
				continue;
			NodeRef v = candidates.get(picked[i]).vehicle();
			joules += lambda[i] * jp[i];
			shares.put(v, lambda[i]);
			used.add(new Use(task.id(), v, lambda[i] * task.lengthMi(), 0, ctx.now(),
					ctx.now() + lambda[i] * ps[i] + fx[i]));
		}
		return new Option("vfc", Placement.split(shares), MvtoraSolver.utility(params, tMax, delay, joules, 0), delay,
				used);
	}

	private Option mec(NodeRef device, TaskRequest task, double tMax, List<Use> uses) {
		NodeRef server = serverOf.get(device);
		if (server == null)
			return null;
		Path up = path(device, server);
		Path down = path(server, device);
		if (up == null || down == null)
			return null;
		double transfer = task.inputBytes() * 8 / up.bps + task.outputBytes() * 8 / down.bps + up.latency
				+ down.latency;
		double headroom = params.beta() + tMax - transfer;
		if (!(headroom > 0))
			return null;
		// Algorithm 2 over the MEC tasks of this server and the new one
		List<Use> onServer = new ArrayList<>();
		for (Use u : uses)
			if (u.node().equals(server) && u.headroom() > 0)
				onServer.add(u);
		double[] work = new double[onServer.size() + 1];
		double[] room = new double[work.length];
		for (int i = 0; i < onServer.size(); i++) {
			work[i] = onServer.get(i).work();
			room[i] = onServer.get(i).headroom();
		}
		work[work.length - 1] = task.lengthMi();
		room[room.length - 1] = headroom;
		var compute = ctx.topology().node(server).spec().compute();
		double[] f = MvtoraSolver.allocate(params.alpha(), params.pricePerMips(), work, room, compute.mipsPerCore(),
				compute.totalMips(), params.accuracy());
		double mips = f[f.length - 1];
		if (!(mips > 0))
			return null;
		double delay = transfer + task.lengthMi() / mips;
		double joules = deviceJoules(up.first, task);
		Use use = new Use(task.id(), server, task.lengthMi(), headroom, ctx.now(), ctx.now() + delay);
		return new Option("mec", Placement.on(server), MvtoraSolver.utility(params, tMax, delay, joules, mips), delay,
				List.of(use));
	}

	/** The bottleneck rate and the latency of the design path, and its first link. */
	private record Path(double bps, double latency, LinkSpec first) {
	}

	private Path path(NodeRef from, NodeRef to) {
		List<NodeRef> hops = ctx.topology().designPath(from, to);
		if (hops.size() < 2)
			return null;
		double bps = Double.POSITIVE_INFINITY;
		double latency = 0;
		LinkSpec first = null;
		for (int i = 0; i + 1 < hops.size(); i++) {
			NodeRef a = hops.get(i);
			NodeRef b = hops.get(i + 1);
			LinkSpec best = null;
			for (LinkSpec l : ctx.topology().linksOf(a))
				if (l.other(a).equals(b) && (best == null || rate(l, a) > rate(best, a)))
					best = l;
			bps = Math.min(bps, rate(best, a));
			latency += ctx.topology().designLatency(best);
			if (first == null)
				first = best;
		}
		return new Path(bps, latency, first);
	}

	/** @return the rate of a link in bits per second when {@code sender} sends */
	private static double rate(LinkSpec l, NodeRef sender) {
		return (l.a().equals(sender) ? l.channel().upMbps() : l.channel().downMbps()) * 1e6;
	}

	/** @return the device's network energy to send the input and receive the output over {@code l} */
	private static double deviceJoules(LinkSpec l, TaskRequest task) {
		return (task.inputBytes() + task.outputBytes()) * 8 * l.channel().joulesPerBit() / 2;
	}

	/** @return the MIPS a new task gets on a node with {@code load} tasks running or queued */
	private static double effectiveMips(int cores, double mipsPerCore, int load) {
		return mipsPerCore * Math.min(1, (double) cores / (load + 1));
	}

	/** @return the extra power of one busy core */
	private static double corePowerW(EnergySpec e, int cores) {
		return (e.maxW() - e.idleW()) / Math.max(1, cores);
	}
}
