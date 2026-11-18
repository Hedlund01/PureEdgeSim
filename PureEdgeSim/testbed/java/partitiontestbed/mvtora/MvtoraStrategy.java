package partitiontestbed.mvtora;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.locationmanager.Location;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;

import partitiontestbed.Attempt;
import partitiontestbed.EdgeServer;
import partitiontestbed.Job;
import partitiontestbed.NetworkPolicy;
import partitiontestbed.OrchestrationStrategy;
import partitiontestbed.Service;
import partitiontestbed.Testbed;

/**
 * MVTORA, the MEC-VFC-aided task offloading and resource allocation of Sun et
 * al. (IEEE Transactions on Mobile Computing 23(9), 2024), as a strategy of the
 * testbed.
 * <p>
 * The paper has three layers: client UAVs that generate tasks, one edge UAV with
 * an MEC server that also acts as the controller of the area, and rescue
 * vehicles on the ground that lend their idle processors (vehicle fog computing,
 * VFC). In each time slot every client either runs its task itself, offloads it
 * whole to the edge UAV, or divides it among the vehicles it reaches. The
 * testbed has the same roles, under other names:
 * <table>
 * <caption>The roles of the paper in the testbed</caption>
 * <tr><th>Paper</th><th>Testbed</th></tr>
 * <tr><td>C-UAV</td><td>any device; it computes locally when it has a
 * processor</td></tr>
 * <tr><td>E-UAV, MEC server and controller</td><td>the access server the device
 * is attached to: each access server is the controller of the devices it
 * serves</td></tr>
 * <tr><td>rescue vehicle, fog node</td><td>a moving edge server the device hears
 * by radio ({@code mvtora.vfc.fog_nodes=mobile}), or any edge server it hears
 * other than its controller ({@code any})</td></tr>
 * </table>
 * <p>
 * Everything the controller knows comes over the network, as the state
 * information the paper's E-UAV collects at the start of a slot:
 * <ol>
 * <li>Every fog node broadcasts a beacon to the devices in its radio range every
 * {@code mvtora.beacon.interval_s}: its speed, its idle cores, its backlog and its
 * position.</li>
 * <li>A device that creates a task sends a request to its controller with the
 * task, its own processor and backlog, its position, and the fog nodes it hears
 * with what their last beacon said.</li>
 * <li>The controller waits {@code mvtora.round.collect_ms} after the first
 * request for the others, and then decides for all of them at once (Algorithm
 * 5): for each device, the utility of local computing and of VFC, with the fog
 * nodes of Algorithm 3 and the division of Algorithm 4, gives its initial
 * decision; the offloading game of Algorithm 1, with the allocation of its idle
 * cores by Algorithm 2, decides which devices offload to it instead. A fog node
 * serves one device per round (constraint 22g).</li>
 * <li>Each device gets its decision and runs the task accordingly: on its own
 * processor, whole on the controller, or divided into one part per fog node,
 * each part sent over the radio link to its node; the task is done when every
 * part is back.</li>
 * </ol>
 * What the paper leaves out, and this strategy settles as the paper would:
 * <ul>
 * <li>Slots: the paper's tasks arrive at the start of a slot; here they arrive at
 * any time, so a round starts with the first request and lasts the collect
 * window.</li>
 * <li>A device out of range of every access server has no controller: it
 * computes locally, the one way it has left. A device that gets no decision
 * within {@code mvtora.round.decision_timeout_ms} does the same, or, without a
 * processor, sends the task to its controller.</li>
 * <li>A stage of an edge pipeline whose input is on a server runs there; one
 * whose input is on the device (the stage before ran locally) is a new task of
 * the device. A stage that hands its output over on the edge goes to one fog node
 * whole, since its output has to be in one place.</li>
 * <li>A part or a task that reaches a host with no instance of its service
 * starts one there and waits for it.</li>
 * <li>No retry: as in the paper, a task whose parts or result the network loses
 * is lost.</li>
 * </ul>
 * The controller allocates its capacity among the tasks offloaded to it as
 * Algorithm 2 says, to decide; the testbed then runs each task on one core, as it
 * does for every strategy. A core is the most one task can use, and the capacity
 * is that of the idle cores, so that the allocation is one the server can give.
 * The rates are the nominal bandwidth of the device's radio
 * ({@code mvtora.rate.model=nominal}: the paper assumes the channel state known),
 * or the LoS channel model of the paper from the positions reported
 * ({@code channel}).
 *
 * @see Mvtora
 * @see MvtoraConfig
 */
public class MvtoraStrategy extends OrchestrationStrategy {

	/** The name it is registered under. */
	public static final String NAME = "MVTORA";

	/** How a task runs. */
	enum Way {
		LOCAL, MEC, VFC
	}

	/** What a fog node broadcasts to the devices it reaches. */
	static final class Beacon {
		final ComputingNode fog;
		final double speed;
		final int idleCores;
		final double backlog;
		final double x;
		final double y;
		final double sentAt;

		Beacon(ComputingNode fog, double speed, int idleCores, double backlog, Location at, double sentAt) {
			this.fog = fog;
			this.speed = speed;
			this.idleCores = idleCores;
			this.backlog = backlog;
			this.x = at.getXPos();
			this.y = at.getYPos();
			this.sentAt = sentAt;
		}
	}

	/** What a device tells its controller about a task. */
	static final class Request {
		final Job job;
		final boolean canComputeLocally;
		final double localSpeed;
		final double localWait;
		final double radioRate;
		final double x;
		final double y;
		final List<Beacon> fogs;

		Request(Job job, boolean canComputeLocally, double localSpeed, double localWait, double radioRate,
				Location at, List<Beacon> fogs) {
			this.job = job;
			this.canComputeLocally = canComputeLocally;
			this.localSpeed = localSpeed;
			this.localWait = localWait;
			this.radioRate = radioRate;
			this.x = at.getXPos();
			this.y = at.getYPos();
			this.fogs = fogs;
		}
	}

	/** What the controller tells a device. */
	static final class Decision {
		final int job;
		final Way way;
		final List<ComputingNode> hosts;
		final double[] shares;

		Decision(int job, Way way, List<ComputingNode> hosts, double[] shares) {
			this.job = job;
			this.way = way;
			this.hosts = hosts;
			this.shares = shares;
		}
	}

	/** A task of a device that waits for its decision. */
	private static final class Pending {
		final Job job;
		final ComputingNode controller;

		Pending(Job job, ComputingNode controller) {
			this.job = job;
			this.controller = controller;
		}
	}

	/** The requests a controller collects for its next round. */
	private static final class Round {
		final List<Request> requests = new ArrayList<>();
		boolean scheduled;
	}

	MvtoraConfig config;
	private Mvtora model;
	/** The servers that act as fog nodes. */
	private final Set<ComputingNode> fogNodes = new HashSet<>();
	/** On each device, the last beacon of each fog node it heard. */
	private final Map<ComputingNode, Map<ComputingNode, Beacon>> beacons = new HashMap<>();
	/** On each device, its tasks waiting for a decision. */
	private final Map<ComputingNode, Map<Integer, Pending>> pending = new HashMap<>();
	/** On each controller, its round in progress. */
	private final Map<ComputingNode, Round> rounds = new HashMap<>();
	/** On each controller, the tasks it sent to itself that have not arrived yet, by when it decided. */
	private final Map<ComputingNode, Map<Integer, Double>> promised = new HashMap<>();
	/** On each node, the attempts it holds: what a node knows of its own load. */
	private final Map<ComputingNode, List<Attempt>> load = new HashMap<>();

	// What MVTORA did, for the report and the timeline
	final Map<Way, Integer> decisions = new LinkedHashMap<>();
	int roundsRun;
	int players;
	int gameIterations;
	int parts;
	int timeouts;
	int detachedLocal;
	int beaconsSent;
	int instancesOnDemand;

	@Override
	public NetworkPolicy network() {
		// A device talks to every access server in range, its controller and the fog
		// nodes it divides its tasks over: the U2U and U2V links of the paper.
		return NetworkPolicy.hierarchical().multiHoming(true);
	}

	@Override
	public void initialize() {
		config = new MvtoraConfig(Testbed.settingsFile("mvtora.properties"));
		model = new Mvtora(config);
		for (Way way : Way.values())
			decisions.put(way, 0);
		for (EdgeServer server : ctx.servers())
			if (server.mobile || config.fogNodes == MvtoraConfig.FogNodes.ANY) {
				fogNodes.add(server.node);
				ctx.schedule(ctx.random().nextDouble() * config.beaconInterval, () -> beacon(server.node));
			}
	}

	// ------------------------------------------------------------ fog nodes

	/** A fog node tells the devices it reaches what it can take. */
	private void beacon(ComputingNode fog) {
		ctx.schedule(config.beaconInterval, () -> beacon(fog));
		if (!ctx.isUp(fog))
			return;
		Beacon beacon = new Beacon(fog, ghz(fog), idleCores(fog), backlog(fog),
				fog.getMobilityModel().getCurrentLocation(), ctx.now());
		for (ComputingNode contact : ctx.radioContacts(fog))
			if (isDevice(contact)) {
				beaconsSent++;
				ctx.send(fog, contact, config.beaconBytes, beacon);
			}
	}

	// --------------------------------------------------------------- device

	@Override
	public void onTaskCreated(ComputingNode device, Job job) {
		EdgeServer controller = ctx.attachment(device);
		if (controller == null) {
			// No controller in range: computing locally is the one way left.
			if (canComputeLocally(device, job.service())) {
				detachedLocal++;
				runOn(device, job, device);
			}
			return;
		}
		List<Beacon> fogs = new ArrayList<>();
		Map<ComputingNode, Beacon> heard = beacons.getOrDefault(device, Map.of());
		for (ComputingNode contact : ctx.radioContacts(device)) {
			Beacon beacon = heard.get(contact);
			if (contact != controller.node && fogNodes.contains(contact) && beacon != null
					&& ctx.now() - beacon.sentAt <= config.beaconMaxAge
					&& job.service().fits(ctx.serverOf(contact).traits))
				fogs.add(beacon);
		}
		Request request = new Request(job, canComputeLocally(device, job.service()), ghz(device), backlog(device),
				nominalRate(device), device.getMobilityModel().getCurrentLocation(), fogs);
		pending.computeIfAbsent(device, key -> new HashMap<>()).put(job.id(), new Pending(job, controller.node));
		ctx.send(device, controller.node, config.requestBytes + (long) config.fogEntryBytes * fogs.size(), request);
		ctx.schedule(config.decisionTimeout, () -> decisionTimedOut(device, job));
	}

	private void decisionTimedOut(ComputingNode device, Job job) {
		Map<Integer, Pending> mine = pending.get(device);
		Pending waiting = mine == null ? null : mine.remove(job.id());
		if (waiting == null)
			return;
		timeouts++;
		if (canComputeLocally(device, job.service()))
			runOn(device, job, device);
		else
			runOn(device, job, waiting.controller);
	}

	private void decided(ComputingNode device, Decision decision) {
		Map<Integer, Pending> mine = pending.get(device);
		Pending waiting = mine == null ? null : mine.remove(decision.job);
		if (waiting == null)
			return;
		Job job = waiting.job;
		switch (decision.way) {
		case LOCAL:
			runOn(device, job, canComputeLocally(device, job.service()) ? device : waiting.controller);
			break;
		case MEC:
			runOn(device, job, waiting.controller);
			break;
		case VFC:
			if (decision.hosts.size() == 1) {
				runOn(device, job, decision.hosts.get(0));
				break;
			}
			ctx.divide(job, decision.shares);
			for (int part = 0; part < decision.hosts.size(); part++)
				track(ctx.dispatchPart(job, part, device, decision.hosts.get(part)));
			parts += decision.hosts.size();
			break;
		default:
			break;
		}
	}

	@Override
	public void onMessage(ComputingNode node, ComputingNode from, Object payload) {
		if (payload instanceof Beacon) {
			Beacon beacon = (Beacon) payload;
			beacons.computeIfAbsent(node, key -> new HashMap<>()).put(beacon.fog, beacon);
		} else if (payload instanceof Decision) {
			decided(node, (Decision) payload);
		} else if (payload instanceof Request) {
			collect(node, (Request) payload);
		}
	}

	// ----------------------------------------------------------- controller

	private void collect(ComputingNode controller, Request request) {
		Round round = rounds.computeIfAbsent(controller, key -> new Round());
		round.requests.add(request);
		if (!round.scheduled) {
			round.scheduled = true;
			ctx.schedule(config.collectWindow, () -> decide(controller));
		}
	}

	/** Algorithm 5, one slot: decides for every request the controller collected. */
	private void decide(ComputingNode controller) {
		Round round = rounds.get(controller);
		if (round == null)
			return;
		List<Request> requests = new ArrayList<>(round.requests);
		round.requests.clear();
		round.scheduled = false;
		if (requests.isEmpty() || !ctx.isUp(controller))
			return;
		roundsRun++;
		players += requests.size();

		// Lines 3 to 13: the initial decision of each device, local or VFC.
		Set<ComputingNode> taken = new HashSet<>();
		List<Mvtora.Player> game = new ArrayList<>();
		List<Way> initial = new ArrayList<>();
		List<List<ComputingNode>> hosts = new ArrayList<>();
		List<double[]> shares = new ArrayList<>();
		for (Request request : requests) {
			Mvtora.Task task = task(request.job);
			double local = request.canComputeLocally ? model.localUtility(task, request.localSpeed, request.localWait)
					: Mvtora.infeasible(Double.POSITIVE_INFINITY);
			List<Beacon> candidates = new ArrayList<>();
			List<Mvtora.Fog> fogs = new ArrayList<>();
			for (Beacon beacon : request.fogs)
				if (!taken.contains(beacon.fog) && beacon.fog != controller) {
					candidates.add(beacon);
					fogs.add(new Mvtora.Fog(fogRate(request, beacon), beacon.speed,
							beacon.idleCores > 0 ? 0 : beacon.backlog));
				}
			int most = request.job.handsOverOnEdge() ? 1 : config.maxFogNodes;
			List<Integer> selected = Mvtora.select(task, fogs, most);
			List<ComputingNode> chosen = new ArrayList<>();
			List<Mvtora.Fog> chosenFogs = new ArrayList<>();
			for (int j : selected) {
				chosen.add(candidates.get(j).fog);
				chosenFogs.add(fogs.get(j));
			}
			double[] lambda = chosenFogs.isEmpty() ? new double[0] : model.divide(task, chosenFogs, ctx.random());
			double vfc = model.fogUtility(task, chosenFogs, lambda);
			boolean useFog = vfc > local;
			if (useFog)
				taken.addAll(chosen);
			initial.add(useFog ? Way.VFC : Way.LOCAL);
			hosts.add(useFog ? chosen : List.of());
			shares.add(useFog ? lambda : new double[0]);
			double mecRate = canHost(controller, request.job.service()) ? mecRate(controller, request) : 0;
			game.add(new Mvtora.Player(task, mecRate, Math.max(local, vfc)));
		}

		// Lines 14 and 15: the offloading game, with Algorithm 2 for the MEC server.
		Mvtora.Outcome outcome = model.play(game, capacity(controller), ghz(controller));
		gameIterations += outcome.iterations;

		// Line 16: the decisions go back to the devices.
		for (int i = 0; i < requests.size(); i++) {
			Request request = requests.get(i);
			Way way = outcome.mec[i] ? Way.MEC : initial.get(i);
			decisions.merge(way, 1, Integer::sum);
			if (way == Way.MEC) {
				if (!ctx.hasInstance(controller, request.job.service())
						&& ctx.startInstance(controller, request.job.service(), null))
					instancesOnDemand++;
				promised.computeIfAbsent(controller, key -> new HashMap<>()).put(request.job.id(), ctx.now());
			}
			ctx.send(controller, request.job.device(), config.decisionBytes,
					new Decision(request.job.id(), way, way == Way.VFC ? hosts.get(i) : List.of(),
							way == Way.VFC ? shares.get(i) : new double[0]));
		}
	}

	/** @return T_n^max: the time a task has left, from now */
	private Mvtora.Task task(Job job) {
		double due = job.createdAt() + job.deadline();
		if (job.requestDeadline() > 0)
			due = Math.min(due, job.requestDeadline());
		return new Mvtora.Task(job.inputBytes() * 8.0, job.length() / 1000, due - ctx.now());
	}

	/** @return R_{n,u}: the rate from a device to its controller */
	private double mecRate(ComputingNode controller, Request request) {
		if (config.rateModel == MvtoraConfig.RateModel.NOMINAL)
			return request.radioRate;
		Location at = controller.getMobilityModel().getCurrentLocation();
		return model.airRate(Math.hypot(at.getXPos() - request.x, at.getYPos() - request.y));
	}

	/** @return R_{n,j}: the rate from a device to a fog node */
	private double fogRate(Request request, Beacon fog) {
		if (config.rateModel == MvtoraConfig.RateModel.NOMINAL)
			return request.radioRate;
		return model.groundRate(Math.hypot(fog.x - request.x, fog.y - request.y));
	}

	/**
	 * @return F_u^max: the speed of the idle cores of a controller, in GHz: those
	 *         not taken by what it holds or by the tasks it accepted in earlier
	 *         rounds that are still to come
	 */
	private double capacity(ComputingNode controller) {
		Map<Integer, Double> accepted = promised.getOrDefault(controller, new HashMap<>());
		accepted.values().removeIf(at -> ctx.now() - at > config.decisionTimeout);
		return ghz(controller) * Math.max(0, cores(controller) - attempts(controller).size() - accepted.size());
	}

	/** @return whether a server can run a service: the traits it advertises, and the memory */
	private boolean canHost(ComputingNode server, Service service) {
		EdgeServer edge = ctx.serverOf(server);
		return edge != null && service.fits(edge.traits)
				&& (ctx.hasInstance(server, service) || ctx.freeMemory(server) >= service.memory);
	}

	// ---------------------------------------------------------------- hosts

	@Override
	public void onTaskArrived(ComputingNode node, Job job) {
		// A stage of a pipeline whose input is here: on the device, a new task of
		// it; on a server, it runs where its input is.
		if (isDevice(node))
			onTaskCreated(node, job);
		else
			runOn(node, job, node);
	}

	@Override
	public void onNoInstance(ComputingNode host, Attempt attempt) {
		Job job = attempt.job();
		if (job.isDone() || !ctx.startInstance(host, job.service(), null))
			return;
		instancesOnDemand++;
		track(attempt.part() == Attempt.WHOLE ? ctx.dispatch(job, host, host)
				: ctx.dispatchPart(job, attempt.part(), host, host));
	}

	@Override
	public void onNodeRestarted(ComputingNode node) {
		rounds.remove(node);
		promised.remove(node);
		load.remove(node);
	}

	/** Runs a task whole on a host, from the node that holds it, starting its instance on the node itself. */
	private void runOn(ComputingNode from, Job job, ComputingNode host) {
		if (host == from && !ctx.hasInstance(host, job.service()) && !ctx.startInstance(host, job.service(), null))
			return;
		track(ctx.dispatch(job, from, host));
	}

	private void track(Attempt attempt) {
		load.computeIfAbsent(attempt.host(), key -> new ArrayList<>()).add(attempt);
		Map<Integer, Double> accepted = promised.get(attempt.host());
		if (accepted != null)
			accepted.remove(attempt.job().id());
	}

	/** @return the attempts a node holds now: on their way, waiting or running */
	private List<Attempt> attempts(ComputingNode node) {
		List<Attempt> mine = load.get(node);
		if (mine == null)
			return List.of();
		for (Iterator<Attempt> it = mine.iterator(); it.hasNext();) {
			Attempt.State state = it.next().state();
			if (state != Attempt.State.SENT && state != Attempt.State.WAITING && state != Attempt.State.RUNNING)
				it.remove();
		}
		return mine;
	}

	/** @return the cores of a node busy or claimed by what it holds, not counting what is on its way */
	private int idleCores(ComputingNode node) {
		int busy = 0;
		for (Attempt attempt : attempts(node))
			if (attempt.state() != Attempt.State.SENT)
				busy++;
		return Math.max(0, cores(node) - busy);
	}

	/** @return how long the work a node holds takes on all its cores, in seconds */
	private double backlog(ComputingNode node) {
		double work = 0;
		for (Attempt attempt : attempts(node))
			if (attempt.state() != Attempt.State.SENT)
				work += attempt.job().length() * attempt.share();
		double mips = node.getMipsPerCore() * cores(node);
		return mips > 0 ? work / mips : 0;
	}

	private static int cores(ComputingNode node) {
		return (int) node.getNumberOfCPUCores();
	}

	/** @return the speed of one core of a node, in GHz: one instruction per cycle */
	private static double ghz(ComputingNode node) {
		return node.getMipsPerCore() / 1000;
	}

	private boolean canComputeLocally(ComputingNode device, Service service) {
		return cores(device) > 0 && device.getMipsPerCore() > 0 && service.fits(Set.of())
				&& (ctx.hasInstance(device, service) || ctx.freeMemory(device) >= service.memory);
	}

	private boolean isDevice(ComputingNode node) {
		return node != ctx.cloud() && ctx.serverOf(node) == null;
	}

	/** @return the nominal bandwidth of a device's radio, as configured, in bits per second */
	private static double nominalRate(ComputingNode device) {
		String radio = device.getEnergyModel().getConnectivityType();
		if ("cellular".equalsIgnoreCase(radio))
			return SimulationParameters.cellularBandwidthBitsPerSecond;
		if ("ethernet".equalsIgnoreCase(radio))
			return SimulationParameters.ethernetBandwidthBitsPerSecond;
		return SimulationParameters.wifiBandwidthBitsPerSecond;
	}

	// --------------------------------------------------------------- output

	@Override
	public List<String> timelineColumns() {
		return List.of("mvtora_local", "mvtora_mec", "mvtora_vfc", "mvtora_timeouts");
	}

	@Override
	public List<String> timelineValues() {
		return List.of(String.valueOf(decisions.get(Way.LOCAL)), String.valueOf(decisions.get(Way.MEC)),
				String.valueOf(decisions.get(Way.VFC)), String.valueOf(timeouts));
	}

	@Override
	public List<String> reportLines() {
		int total = decisions.values().stream().mapToInt(Integer::intValue).sum();
		return List.of(
				String.format(Locale.ROOT, "MVTORA: %d decisions in %d rounds (%.1f tasks per round, %.1f game "
						+ "iterations): %d local, %d MEC, %d VFC in %d parts", total, roundsRun,
						roundsRun == 0 ? 0.0 : (double) players / roundsRun,
						roundsRun == 0 ? 0.0 : (double) gameIterations / roundsRun, decisions.get(Way.LOCAL),
						decisions.get(Way.MEC), decisions.get(Way.VFC), parts),
				String.format(Locale.ROOT, "MVTORA: %d tasks computed locally out of range, %d decisions timed out, "
						+ "%d beacons, %d instances started on demand", detachedLocal, timeouts, beaconsSent,
						instancesOnDemand));
	}
}
