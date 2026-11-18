package partitiontestbed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationengine.SimEntity;
import com.mechalikh.pureedgesim.taskgenerator.DefaultTask;
import com.mechalikh.pureedgesim.taskgenerator.Task;

/**
 * Carries out what the strategy decides, and keeps the ledger of every task.
 * <p>
 * It is the {@link StrategyContext} of the run: it puts messages, requests,
 * attempts and results on the network as {@link Carrier}s, runs attempts on the
 * nodes of the framework, starts and stops instances, and calls the strategy back
 * when something arrives, runs or changes. It knows the truth (the live topology,
 * every attempt), and gives the strategy none of it.
 * <p>
 * Every task of the devices becomes a {@link Job}, judged once at the end of the
 * run. The engine also marks what happened around each job while it was not done
 * ({@link Job.Exposure}), so that the metrics can tell how a strategy coped with
 * a device that left, a host that failed or a partition that started.
 */
final class TaskEngine extends SimEntity implements StrategyContext {

	private static final int TIMER = 1;
	private static final int DELIVER = 2;
	private static final int INSTANCE_READY = 3;

	/** An instance of a service on a node. */
	private static final class Instance {
		final Service service;
		boolean ready;
		final List<Runnable> onReady = new ArrayList<>();
		final List<Attempt> waiting = new ArrayList<>();

		Instance(Service service) {
			this.service = service;
		}
	}

	/** An RPC in progress, on the side of its caller. */
	private static final class Call {
		final Consumer<Object> onReply;
		final Runnable onTimeout;

		Call(Consumer<Object> onReply, Runnable onTimeout) {
			this.onReply = onReply;
			this.onTimeout = onTimeout;
		}
	}

	private final TestbedWorld world;
	private final TestbedSimulationManager manager;
	private final OrchestrationStrategy strategy;
	private final Random random;

	/** Every task of the run, in the order they were created. */
	private final List<Job> jobs = new ArrayList<>();
	private final Map<Task, Job> jobOf = new IdentityHashMap<>();
	/** The jobs of each device that are not done. */
	private final Map<ComputingNode, Set<Job>> open = new HashMap<>();
	/** The attempts running on a node, by the task the node runs. */
	private final Map<Task, Attempt> running = new IdentityHashMap<>();
	private final Map<ComputingNode, Map<Service, Instance>> instances = new HashMap<>();
	private final Map<ComputingNode, Double> memoryUsed = new HashMap<>();
	private final Map<Long, Call> calls = new HashMap<>();
	private final Set<ComputingNode> crashed = Collections.newSetFromMap(new IdentityHashMap<>());
	/** The last attempt dispatched for each device, for the deployment map. */
	private final Map<ComputingNode, Attempt> lastAttempt = new LinkedHashMap<>();
	private long rpcCounter;

	// Counters for the metrics
	long controlBytes;
	long dataBytes;
	int controlLost;
	int dataLost;
	int messagesSent;
	int attemptsDispatched;
	int attemptsRun;
	int attemptsLost;
	int instancesStarted;
	int instancesRefused;
	double peakMemory;
	double busyMemoryIntegral;
	private double lastMemoryChange;

	TaskEngine(TestbedWorld world, TestbedSimulationManager manager, OrchestrationStrategy strategy) {
		super(manager.getSimulation());
		this.world = world;
		this.manager = manager;
		this.strategy = strategy;
		// The strategy's own source, so that what it draws changes nothing the
		// testbed draws.
		this.random = new Random(world.seed() ^ 0x5EED5EEDL);
		strategy.bind(this);
	}

	// ------------------------------------------------------------- events

	@Override
	protected void processEvent(Event e) {
		switch (e.getTag()) {
		case TIMER:
			((Runnable) e.getData()).run();
			break;
		case DELIVER:
			arrived((Carrier) e.getData());
			break;
		case INSTANCE_READY:
			Object[] data = (Object[]) e.getData();
			instanceReady((ComputingNode) data[0], (Instance) data[1]);
			break;
		default:
			break;
		}
	}

	// ------------------------------------------------------- from the testbed

	/** A device created a task: the first stage of a request, or a task on its own. */
	void created(Task task) {
		Job job = newJob(task);
		strategy.onTaskCreated(job.device(), job);
	}

	private Job newJob(Task task) {
		ComputingNode device = task.getEdgeDevice();
		Job job = new Job(task, device, world.services().get(task.getApplicationID()), now(),
				!world.activePartitions().isEmpty());
		EdgeServer home = world.homeOf(device);
		for (TestbedWorld.ActivePartition partition : world.activePartitions()) {
			Integer group = home == null ? null : partition.groupOf.get(home);
			if (group != null)
				job.groups.put(partition, group);
		}
		// A device out of range when it creates a task meets the trouble at once.
		if (!world.attached(device))
			job.exposures.add(Job.Exposure.DEVICE_DETACHED);
		jobs.add(job);
		jobOf.put(task, job);
		open.computeIfAbsent(device, key -> new LinkedHashSet<>()).add(job);
		return job;
	}

	/** A node finished running a task of the engine. */
	void executed(Task task) {
		Attempt attempt = running.remove(task);
		if (attempt == null || attempt.state != Attempt.State.RUNNING)
			return;
		attempt.state = Attempt.State.RAN;
		attempt.ranAt = now();
		attemptsRun++;
		strategy.onExecuted(attempt.host(), attempt);
	}

	/** A carrier reached its destination. */
	void arrived(Carrier carrier) {
		if (!isUp(carrier.to)) {
			lost(carrier);
			return;
		}
		switch (carrier.kind) {
		case MESSAGE:
			strategy.onMessage(carrier.to, carrier.from, carrier.payload);
			break;
		case RPC_REQUEST:
			StrategyContext.Reply reply = strategy.onRpc(carrier.to, carrier.from, carrier.payload);
			if (reply != null)
				put(new Carrier(Carrier.Kind.RPC_REPLY, carrier.to, carrier.from, reply.bytes, reply.payload,
						carrier.rpc));
			break;
		case RPC_REPLY:
			Call call = calls.remove(carrier.rpc);
			if (call != null)
				call.onReply.accept(carrier.payload);
			break;
		case REQUEST:
			strategy.onTaskArrived(carrier.to, (Job) carrier.payload);
			break;
		case DISPATCH:
			attemptArrived((Attempt) carrier.payload);
			break;
		case RESULT:
			resultArrived((Attempt) carrier.payload);
			break;
		default:
			break;
		}
	}

	/** A carrier found no path, or lost the one it had. */
	void lost(Carrier carrier) {
		if (carrier.control())
			controlLost++;
		else
			dataLost++;
		if (carrier.kind == Carrier.Kind.DISPATCH) {
			Attempt attempt = (Attempt) carrier.payload;
			if (attempt.state == Attempt.State.SENT) {
				attempt.state = Attempt.State.LOST;
				attemptsLost++;
			}
		}
	}

	// ---------------------------------------------------------- exposures

	/**
	 * @return the tasks of a device that are in flight: not done, and within their
	 *         deadline, the time their device expects them to take. An event later
	 *         than that did not meet the task in flight.
	 */
	private Set<Job> inFlight(ComputingNode device) {
		Set<Job> jobsOfDevice = open.get(device);
		if (jobsOfDevice == null)
			return Set.of();
		double now = now();
		jobsOfDevice.removeIf(job -> now > job.createdAt() + job.deadline());
		return jobsOfDevice;
	}

	/** Marks every task of a device that is in flight. */
	void expose(ComputingNode device, Job.Exposure exposure) {
		for (Job job : inFlight(device))
			job.exposures.add(exposure);
	}

	/** Marks every task that is in flight. */
	void exposeAll(Job.Exposure exposure) {
		for (ComputingNode device : open.keySet())
			for (Job job : inFlight(device))
				job.exposures.add(exposure);
	}

	/**
	 * A server failed: marks the tasks that had an attempt on it, and, when it
	 * crashed, loses those attempts and its instances.
	 */
	void nodeFailed(ComputingNode node) {
		for (ComputingNode device : open.keySet())
			for (Job job : inFlight(device))
				for (Attempt attempt : job.attempts)
					if (attempt.host() == node && attempt.state != Attempt.State.LOST
							&& attempt.state != Attempt.State.CANCELLED)
						job.exposures.add(Job.Exposure.HOST_FAILED);
		if (!world.config.nodeCrash)
			return;
		crashed.add(node);
		for (Attempt attempt : new ArrayList<>(running.values()))
			if (attempt.host() == node)
				loseAttempt(attempt);
		Map<Service, Instance> onNode = instances.remove(node);
		if (onNode != null)
			for (Instance instance : onNode.values())
				for (Attempt attempt : instance.waiting)
					loseAttempt(attempt);
		setMemory(node, 0);
	}

	/** A server that failed runs again. */
	void nodeRepaired(ComputingNode node) {
		if (crashed.remove(node))
			strategy.onNodeRestarted(node);
	}

	private void loseAttempt(Attempt attempt) {
		if (attempt.state == Attempt.State.LOST || attempt.state == Attempt.State.CANCELLED
				|| attempt.state == Attempt.State.RAN)
			return;
		attempt.state = Attempt.State.LOST;
		attemptsLost++;
	}

	// --------------------------------------------------------------- time

	@Override
	public double now() {
		return getSimulation().clock();
	}

	@Override
	public void schedule(double delay, Runnable action) {
		schedule(this, Math.max(0, delay), TIMER, action);
	}

	@Override
	public Random random() {
		return random;
	}

	// ------------------------------------------------------------- layout

	@Override
	public List<EdgeServer> servers() {
		return world.servers();
	}

	@Override
	public List<EdgeServer> accessServers() {
		return world.accessServers();
	}

	@Override
	public EdgeServer serverOf(ComputingNode node) {
		return world.serverOf(node);
	}

	@Override
	public ComputingNode cloud() {
		return world.cloud();
	}

	@Override
	public Set<Trait> cloudTraits() {
		return world.config.cloudTraits;
	}

	@Override
	public List<EdgeServer> meshNeighbours(EdgeServer server) {
		List<EdgeServer> neighbours = new ArrayList<>();
		for (ComputingNode[] pair : world.meshPairs) {
			if (pair[0] == server.node)
				neighbours.add(world.serverOf(pair[1]));
			else if (pair[1] == server.node)
				neighbours.add(world.serverOf(pair[0]));
		}
		return neighbours;
	}

	@Override
	public List<Service> services() {
		return world.services();
	}

	@Override
	public String deviceType(ComputingNode device) {
		TestbedWorld.DeviceType type = world.deviceTypeOf(device);
		return type == null ? "" : type.name;
	}

	// -------------------------------------------------------------- local

	@Override
	public EdgeServer attachment(ComputingNode device) {
		return world.attached(device) ? world.homeOf(device) : null;
	}

	@Override
	public List<ComputingNode> radioContacts(ComputingNode node) {
		return world.radioContacts(node);
	}

	@Override
	public void setRelay(ComputingNode node, boolean relaying) {
		world.topology().setRelay(node, relaying);
	}

	@Override
	public boolean isUp(ComputingNode node) {
		return !crashed.contains(node);
	}

	// ------------------------------------------------------------ messages

	@Override
	public void send(ComputingNode from, ComputingNode to, long bytes, Object payload) {
		messagesSent++;
		put(new Carrier(Carrier.Kind.MESSAGE, from, to, bytes, payload, 0));
	}

	@Override
	public void rpc(ComputingNode from, ComputingNode to, long bytes, Object request, double timeout,
			Consumer<Object> onReply, Runnable onTimeout) {
		long id = ++rpcCounter;
		calls.put(id, new Call(onReply, onTimeout));
		messagesSent++;
		put(new Carrier(Carrier.Kind.RPC_REQUEST, from, to, bytes, request, id));
		schedule(timeout, () -> {
			Call call = calls.remove(id);
			if (call != null)
				call.onTimeout.run();
		});
	}

	/** Puts a carrier on the network, or delivers it at once when it stays on its node. */
	private void put(Carrier carrier) {
		if (!isUp(carrier.from)) {
			lost(carrier);
			return;
		}
		if (carrier.control())
			controlBytes += carrier.bytes;
		else
			dataBytes += carrier.bytes;
		if (carrier.from == carrier.to) {
			scheduleNow(this, DELIVER, carrier);
			return;
		}
		((TestbedNetworkModel) manager.getNetworkModel()).send(carrier);
	}

	// --------------------------------------------------------------- tasks

	@Override
	public void sendRequest(Job job, ComputingNode device, ComputingNode server) {
		put(new Carrier(Carrier.Kind.REQUEST, device, server, job.inputBytes(), job, 0));
	}

	@Override
	public Attempt dispatch(Job job, ComputingNode from, ComputingNode host) {
		return dispatch(new Attempt(job, from, host));
	}

	@Override
	public void divide(Job job, double... shares) {
		if (job.isDivided())
			throw new IllegalStateException(job + " is already divided");
		if (job.handsOverOnEdge())
			throw new IllegalStateException(job + " hands its output over on the edge and cannot be divided");
		if (shares.length == 0)
			throw new IllegalArgumentException("a task is divided into one part at least");
		double total = 0;
		for (double share : shares) {
			if (!(share > 0) || Double.isInfinite(share))
				throw new IllegalArgumentException("the share of a part must be positive: " + share);
			total += share;
		}
		double[] normalized = new double[shares.length];
		for (int i = 0; i < shares.length; i++)
			normalized[i] = shares[i] / total;
		job.divide(normalized);
	}

	@Override
	public Attempt dispatchPart(Job job, int part, ComputingNode from, ComputingNode host) {
		if (!job.isDivided())
			throw new IllegalStateException(job + " is not divided");
		if (part < 0 || part >= job.parts())
			throw new IllegalArgumentException(job + " has no part " + part);
		return dispatch(new Attempt(job, from, host, part));
	}

	private Attempt dispatch(Attempt attempt) {
		Job job = attempt.job();
		job.attempts.add(attempt);
		attemptsDispatched++;
		lastAttempt.put(job.device(), attempt);
		put(new Carrier(Carrier.Kind.DISPATCH, attempt.from(), attempt.host(), scaled(job.inputBytes(), attempt),
				attempt, 0));
		return attempt;
	}

	/** @return the size of what an attempt carries: its share of the size of its task */
	private static long scaled(long bytes, Attempt attempt) {
		return attempt.part() == Attempt.WHOLE ? bytes : (long) Math.ceil(bytes * attempt.share());
	}

	private void attemptArrived(Attempt attempt) {
		if (attempt.state != Attempt.State.SENT)
			return;
		Instance instance = instance(attempt.host(), attempt.job().service());
		if (instance == null) {
			attempt.state = Attempt.State.LOST;
			attemptsLost++;
			strategy.onNoInstance(attempt.host(), attempt);
			return;
		}
		attempt.state = Attempt.State.WAITING;
		if (instance.ready)
			run(attempt);
		else
			instance.waiting.add(attempt);
	}

	/** Runs an attempt on its host, which queues it while its cores are busy. */
	private void run(Attempt attempt) {
		Task source = attempt.job().task();
		DefaultTask work = new DefaultTask(source.getId());
		work.setLength(source.getLength() * attempt.share());
		work.setFileSizeInBits(0);
		work.setOutputSizeInBits((long) (source.getOutputSizeInBits() * attempt.share()));
		work.setContainerSizeInBits(0);
		work.setEdgeDevice(source.getEdgeDevice());
		work.setApplicationID(source.getApplicationID());
		work.setMaxLatency(source.getMaxLatency());
		work.setTime(now());
		work.setOffloadingDestination(attempt.host());
		attempt.state = Attempt.State.RUNNING;
		running.put(work, attempt);
		attempt.host().submitTask(work);
	}

	@Override
	public void sendResult(Attempt attempt, ComputingNode from) {
		if (!attempt.job().returnsResult())
			throw new IllegalStateException(attempt + " returns nothing to its device: finish it at its host");
		put(new Carrier(Carrier.Kind.RESULT, from, attempt.job().device(), scaled(attempt.job().outputBytes(), attempt),
				attempt, 0));
	}

	private void resultArrived(Attempt attempt) {
		Job job = attempt.job();
		boolean first = !isDone(job, attempt);
		boolean last = first && job.partDone(attempt);
		if (last)
			done(job, attempt);
		else if (!first)
			job.duplicates++;
		strategy.onResultReceived(job.device(), attempt, first);
		// The device sends the next stage of a pipeline that goes through it.
		if (last)
			release(job, job.device(), false);
	}

	/** @return whether what an attempt runs, its task or its part, is done already */
	private static boolean isDone(Job job, Attempt attempt) {
		return attempt.part() == Attempt.WHOLE ? job.isDone() : job.isPartDone(attempt.part());
	}

	@Override
	public void finishAtHost(Attempt attempt) {
		Job job = attempt.job();
		if (job.returnsResult())
			throw new IllegalStateException(attempt + " must bring its result back to its device");
		if (attempt.state != Attempt.State.RAN)
			throw new IllegalStateException(attempt + " has not run");
		if (isDone(job, attempt)) {
			job.duplicates++;
			return;
		}
		if (!job.partDone(attempt))
			return;
		done(job, attempt);
		release(job, attempt.host(), job.handsOverOnEdge());
	}

	private void done(Job job, Attempt attempt) {
		job.done(now(), attempt);
		Set<Job> jobsOfDevice = open.get(job.device());
		if (jobsOfDevice != null)
			jobsOfDevice.remove(job);
		manager.jobDone();
	}

	/**
	 * Releases the stages that waited for a task, with its output added to what
	 * they carry: at its host, for an edge pipeline, or on the device otherwise.
	 */
	private void release(Job job, ComputingNode at, boolean onEdge) {
		for (Task next : job.task().getDependents()) {
			next.setFileSizeInBits((long) (next.getFileSizeInBits() + job.task().getOutputSizeInBits()));
			if (!next.dependencyFinished())
				continue;
			next.setTime(now());
			Job nextJob = newJob(next);
			if (onEdge)
				strategy.onTaskArrived(at, nextJob);
			else
				strategy.onTaskCreated(nextJob.device(), nextJob);
		}
	}

	@Override
	public void cancel(Attempt attempt) {
		if (attempt.state == Attempt.State.RAN || attempt.state == Attempt.State.LOST)
			return;
		attempt.state = Attempt.State.CANCELLED;
		Map<Service, Instance> onNode = instances.get(attempt.host());
		if (onNode != null)
			for (Instance instance : onNode.values())
				instance.waiting.remove(attempt);
	}

	// ----------------------------------------------------------- instances

	private Instance instance(ComputingNode host, Service service) {
		Map<Service, Instance> onNode = instances.get(host);
		return onNode == null ? null : onNode.get(service);
	}

	@Override
	public boolean startInstance(ComputingNode host, Service service, Runnable onReady) {
		if (!isUp(host) || !fits(host, service))
			return false;
		Instance instance = instance(host, service);
		if (instance != null) {
			if (onReady != null) {
				if (instance.ready)
					schedule(0, onReady);
				else
					instance.onReady.add(onReady);
			}
			return true;
		}
		if (freeMemory(host) < service.memory) {
			instancesRefused++;
			return false;
		}
		instance = new Instance(service);
		if (onReady != null)
			instance.onReady.add(onReady);
		instances.computeIfAbsent(host, key -> new LinkedHashMap<>()).put(service, instance);
		setMemory(host, memoryUsed.getOrDefault(host, 0.0) + service.memory);
		instancesStarted++;
		schedule(this, service.startup, INSTANCE_READY, new Object[] { host, instance });
		return true;
	}

	/**
	 * @return whether the host has the traits the service requires: the cloud's, or
	 *         an edge server's; a device, which advertises none, runs a service
	 *         that requires none when it has a processor
	 */
	private boolean fits(ComputingNode host, Service service) {
		if (host == world.cloud())
			return service.fits(world.config.cloudTraits);
		if (world.deviceTypeOf(host) != null)
			return host.getNumberOfCPUCores() > 0 && host.getMipsPerCore() > 0 && service.fits(Set.of());
		EdgeServer server = world.serverOf(host);
		return server != null && service.fits(server.traits);
	}

	private void instanceReady(ComputingNode host, Instance instance) {
		if (instance(host, instance.service) != instance)
			return;
		instance.ready = true;
		for (Attempt attempt : instance.waiting)
			if (attempt.state == Attempt.State.WAITING)
				run(attempt);
		instance.waiting.clear();
		for (Runnable action : instance.onReady)
			action.run();
		instance.onReady.clear();
	}

	@Override
	public void stopInstance(ComputingNode host, Service service) {
		Map<Service, Instance> onNode = instances.get(host);
		Instance instance = onNode == null ? null : onNode.remove(service);
		if (instance == null)
			return;
		for (Attempt attempt : instance.waiting)
			loseAttempt(attempt);
		setMemory(host, memoryUsed.getOrDefault(host, 0.0) - service.memory);
	}

	@Override
	public boolean hasInstance(ComputingNode host, Service service) {
		return instance(host, service) != null;
	}

	@Override
	public double freeMemory(ComputingNode host) {
		if (host == world.cloud())
			return Double.MAX_VALUE;
		return host.getRamCapacity() - memoryUsed.getOrDefault(host, 0.0);
	}

	/** Sets the memory held on an edge server, keeping the peak and the time integral of the total. */
	private void setMemory(ComputingNode node, double used) {
		if (node == world.cloud())
			return;
		closeMemoryIntegral();
		memoryUsed.put(node, Math.max(0, used));
		peakMemory = Math.max(peakMemory, memoryTotal());
	}

	/** Adds the memory held since the last change to its time integral. */
	void closeMemoryIntegral() {
		busyMemoryIntegral += memoryTotal() * (now() - lastMemoryChange);
		lastMemoryChange = now();
	}

	/** @return the memory held by instances on the edge servers now, in megabytes */
	double memoryTotal() {
		double total = 0;
		for (Map.Entry<ComputingNode, Double> entry : memoryUsed.entrySet())
			if (world.deviceTypeOf(entry.getKey()) == null)
				total += entry.getValue();
		return total;
	}

	/** @return the number of instances on the edge servers and the cloud now */
	int instanceCount() {
		int count = 0;
		for (Map<Service, Instance> onNode : instances.values())
			count += onNode.size();
		return count;
	}

	// -------------------------------------------------------------- ledger

	/**
	 * Adds to the ledger the stages that were never released, because a stage
	 * before them was never done, so that every strategy is judged on the same
	 * tasks: such a stage is not done, created when the stage that held it back
	 * was.
	 */
	void closeLedger() {
		for (int i = 0; i < jobs.size(); i++) {
			Job job = jobs.get(i);
			for (Task next : job.task().getDependents())
				if (!jobOf.containsKey(next) && !job.isDone()) {
					Job never = new Job(next, job.device(), world.services().get(next.getApplicationID()),
							job.createdAt(), job.partitioned);
					never.groups.putAll(job.groups);
					never.exposures.addAll(job.exposures);
					jobs.add(never);
					jobOf.put(next, never);
				}
		}
	}

	/** @return the testbed, for the tests: a strategy never sees it */
	TestbedWorld world() {
		return world;
	}

	/** @return every task of the run */
	List<Job> jobs() {
		return Collections.unmodifiableList(jobs);
	}

	/** @return the last attempt dispatched for each device */
	Map<ComputingNode, Attempt> lastAttempts() {
		return Collections.unmodifiableMap(lastAttempt);
	}
}
