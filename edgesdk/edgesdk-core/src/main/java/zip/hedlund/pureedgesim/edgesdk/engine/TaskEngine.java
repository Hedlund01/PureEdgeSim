package zip.hedlund.pureedgesim.edgesdk.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskReport;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.metrics.TaskOutcome;
import zip.hedlund.pureedgesim.edgesdk.metrics.TaskRecord;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.WorkloadSpec;
import zip.hedlund.pureedgesim.edgesdk.network.LossReason;
import zip.hedlund.pureedgesim.edgesdk.network.Transfer;

/**
 * The life of a task: created at its device (the workload is drawn before the
 * run from the workload seed), placed by the policy, sent as input, executed on a
 * PureEdgeSim node, returned as a result. A split attempt runs its parts in
 * parallel and has a result once every part's result is back. The first attempt
 * with a result wins; the deadline is checked at the device.
 */
final class TaskEngine {

	/** One placement of a task: one part, or several for a split. */
	static final class Attempt {
		final State state;
		final List<Part> parts = new ArrayList<>();
		final int number;
		int pending;

		Attempt(State state, int number) {
			this.state = state;
			this.number = number;
		}

		/** @return the node of the largest part */
		SdkNode main() {
			Part best = parts.get(0);
			for (Part p : parts)
				if (p.share > best.share)
					best = p;
			return best.target;
		}
	}

	/** The share of an attempt that runs on one node. */
	static final class Part {
		final Attempt attempt;
		final SdkNode target;
		final double share;
		boolean returned;

		Part(Attempt attempt, SdkNode target, double share) {
			this.attempt = attempt;
			this.target = target;
			this.share = share;
		}

		long scale(long bytes) {
			// a part of a non-empty transfer carries at least a byte
			return share == 1 || bytes == 0 ? bytes : Math.max(1, Math.round(bytes * share));
		}
	}

	/** The state of a task. */
	static final class State {
		final TaskRequest request;
		final WorkloadSpec workload;
		int deviceEpoch;
		int attempts;
		boolean rejected;
		boolean deviceDown;
		boolean deadlinePassed;
		/** The result arrived at or after the deadline, which counted the task as failed. */
		boolean late;
		/**
		 * The first loss of any attempt: the cause that made the task need a retry.
		 * Later losses of retries (often a consequence, e.g. NO_ROUTE after the link
		 * was lost) do not replace it.
		 */
		TaskOutcome firstLoss;
		Attempt executed;
		double completedAt = Double.NaN;

		State(TaskRequest request, WorkloadSpec workload) {
			this.request = request;
			this.workload = workload;
		}

		void lost(TaskOutcome cause) {
			if (firstLoss == null)
				firstLoss = cause;
		}

		boolean hasResult() {
			return !Double.isNaN(completedAt);
		}
	}

	private final SdkWorld world;
	private final AlgorithmRuntime runtime;
	private final OrchestrationPolicy policy;
	private final List<State> states = new ArrayList<>();
	private final Map<SdkNode, Set<Part>> executing = new HashMap<>();
	private int nextCoreTaskId;
	private int doneInWindow;
	private int failedInWindow;
	private int succeeded;
	private int finished;

	TaskEngine(SdkWorld world, AlgorithmRuntime runtime, OrchestrationPolicy policy) {
		this.world = world;
		this.runtime = runtime;
		this.policy = policy;
	}

	/** Draws every task of the run and schedules its creation. */
	void start() {
		Scenario s = world.scenario();
		long id = 0;
		List<State> drawn = new ArrayList<>();
		for (Scenario.WorkloadAssignment w : s.workloads()) {
			for (NodeRef device : w.devices().resolve(world.design())) {
				Random r = new Random(world.spec().workloadSeed() ^ (device.name() + "/" + w.workload().name())
						.hashCode() * 0x9E3779B97F4A7C15L);
				double mean = 60.0 / w.workload().ratePerMinute();
				double t = 0;
				while (true) {
					t += -Math.log(1 - r.nextDouble()) * mean;
					if (t + w.workload().deadlineS() > s.duration())
						break;
					drawn.add(new State(new TaskRequest(0, device, w.workload().name(), w.workload().lengthMi(),
							w.workload().inputBytes(), w.workload().outputBytes(), t, t + w.workload().deadlineS()),
							w.workload()));
				}
			}
		}
		drawn.sort(Comparator.comparingDouble((State st) -> st.request.createdAt())
				.thenComparing(st -> st.request.device().name()));
		for (State st : drawn) {
			TaskRequest r = st.request;
			State numbered = new State(new TaskRequest(id++, r.device(), r.workload(), r.lengthMi(), r.inputBytes(),
					r.outputBytes(), r.createdAt(), r.deadline()), st.workload);
			states.add(numbered);
			world.at(r.createdAt(), () -> created(numbered));
			world.at(r.deadline(), () -> deadline(numbered));
		}
	}

	private void created(State s) {
		SdkNode device = world.node(s.request.device());
		if (!device.isUp()) {
			s.deviceDown = true;
			return;
		}
		s.deviceEpoch = device.epoch();
		attempt(s, policy.place(device.ref(), s.request));
	}

	private void attempt(State s, Placement p) {
		if (p == null || p.isRejected()) {
			if (s.attempts == 0)
				s.rejected = true;
			return;
		}
		SdkNode device = world.node(s.request.device());
		Attempt a = new Attempt(s, ++s.attempts);
		if (p.isSplit())
			p.shares().forEach((node, share) -> a.parts.add(new Part(a, world.node(node), share)));
		else
			a.parts.add(new Part(a, p.isLocal() ? device : world.node(p.node().get()), 1));
		a.pending = a.parts.size();
		if (p.retryAfter() > 0)
			runtime.schedule(device.ref(), p.retryAfter(), () -> {
				if (!s.hasResult() && !s.deadlinePassed && device.epoch() == s.deviceEpoch)
					attempt(s, policy.onAttemptTimedOut(device.ref(), s.request, waitingFor(a), s.attempts));
			});
		for (Part part : a.parts) {
			if (part.target == device) {
				execute(part);
				continue;
			}
			world.network().send(device, part.target, part.scale(s.request.inputBytes()), Transfer.Kind.TASK_INPUT,
					t -> execute(part), (t, reason) -> s.lost(map(reason)));
		}
	}

	/** @return the node of the first part of the attempt whose result is not back */
	private static NodeRef waitingFor(Attempt a) {
		for (Part p : a.parts)
			if (!p.returned)
				return p.target.ref();
		return a.parts.get(0).target.ref();
	}

	private void execute(Part p) {
		State s = p.attempt.state;
		if (!p.target.isUp()) {
			s.lost(TaskOutcome.NODE_CRASHED);
			return;
		}
		SdkTask task = new SdkTask(nextCoreTaskId++, p);
		task.setLength(s.request.lengthMi() * p.share);
		task.setContainerSizeInBits((long) (s.workload.containerMb() * 8_000_000L));
		task.setFileSizeInBits(p.scale(s.request.inputBytes()) * 8);
		task.setOutputSizeInBits(p.scale(s.request.outputBytes()) * 8);
		task.setMaxLatency(s.workload.deadlineS());
		task.setEdgeDevice(world.node(s.request.device()));
		task.setOffloadingDestination(p.target);
		executing.computeIfAbsent(p.target, k -> new LinkedHashSet<>()).add(p);
		p.target.submitTask(task);
	}

	/** PureEdgeSim finished executing a task. */
	void onExecuted(SdkTask task) {
		Part p = task.part;
		Set<Part> running = executing.get(p.target);
		if (running == null || !running.remove(p))
			return; // the node crashed in between
		State s = p.attempt.state;
		SdkNode device = world.node(s.request.device());
		if (p.target == device) {
			partArrived(p);
			return;
		}
		world.network().send(p.target, device, p.scale(s.request.outputBytes()), Transfer.Kind.TASK_RESULT, t -> {
			if (device.epoch() == s.deviceEpoch)
				partArrived(p);
			else
				s.lost(TaskOutcome.DEVICE_DOWN);
		}, (t, reason) -> s.lost(map(reason)));
	}

	private void partArrived(Part p) {
		if (p.returned)
			return;
		p.returned = true;
		if (--p.attempt.pending == 0)
			resultArrived(p.attempt);
	}

	private void resultArrived(Attempt a) {
		State s = a.state;
		if (s.hasResult())
			return;
		// the deadline is exclusive: a result at the deadline instant is late, whichever event runs first
		if (!s.deadlinePassed && world.now() >= s.request.deadline())
			deadline(s);
		s.completedAt = world.now();
		s.executed = a;
		// deadline() already counted the task as failed
		s.late = s.deadlinePassed;
		if (!s.late) {
			doneInWindow++;
			succeeded++;
			finished++;
		}
		policy.onTaskCompleted(s.request.device(), new TaskReport(s.request, a.main().ref(), s.completedAt,
				s.attempts, s.late, a.parts.stream().map(p -> p.target.ref()).toList()));
	}

	private void deadline(State s) {
		s.deadlinePassed = true;
		if (s.hasResult())
			return;
		failedInWindow++;
		finished++;
		SdkNode device = world.node(s.request.device());
		if (!s.deviceDown && !s.rejected && (!device.isUp() || device.epoch() != s.deviceEpoch))
			s.deviceDown = true;
		if (device.isUp() && !s.deviceDown && !s.rejected)
			policy.onTaskTimedOut(device.ref(), s.request);
	}

	void onNodeCrashed(SdkNode node) {
		Set<Part> running = executing.remove(node);
		if (running != null)
			for (Part p : running)
				p.attempt.state.lost(TaskOutcome.NODE_CRASHED);
	}

	private static TaskOutcome map(LossReason reason) {
		return switch (reason) {
		case NO_ROUTE -> TaskOutcome.NO_ROUTE;
		case LINK_LOST -> TaskOutcome.LINK_LOST;
		case PACKET_LOSS -> TaskOutcome.PACKET_LOSS;
		case ENDPOINT_DOWN -> TaskOutcome.NODE_CRASHED;
		};
	}

	/** @return tasks finished since the last call: {done, failed} */
	int[] drainWindow() {
		int[] w = { doneInWindow, failedInWindow };
		doneInWindow = 0;
		failedInWindow = 0;
		return w;
	}

	/**
	 * The share of finished tasks that failed. A task finishes either when its
	 * result arrives before its deadline (success) or when its deadline passes
	 * without a result (failure). The deadline is exclusive: a result that arrives
	 * at or after it does not change that, whatever order the simulator runs the
	 * two events in at a tie; the task stays a failure, recorded as
	 * {@link TaskOutcome#LATE}. The timeline windows count the same way.
	 */
	double failurePct() {
		return finished == 0 ? 0 : 100.0 * (finished - succeeded) / finished;
	}

	List<TaskRecord> records() {
		List<TaskRecord> out = new ArrayList<>(states.size());
		for (State s : states) {
			TaskOutcome outcome;
			if (s.hasResult())
				outcome = s.late ? TaskOutcome.LATE : TaskOutcome.SUCCESS;
			else if (s.deviceDown)
				outcome = TaskOutcome.DEVICE_DOWN;
			else if (s.rejected)
				outcome = TaskOutcome.REJECTED;
			else if (s.firstLoss != null)
				outcome = s.firstLoss;
			else
				outcome = TaskOutcome.TIMED_OUT;
			double created = s.request.createdAt();
			out.add(new TaskRecord(s.request.id(), s.request.device().name(), s.request.workload(), created,
					s.request.deadline(), outcome, s.executed == null ? "" : s.executed.main().decl().name(),
					s.completedAt, s.attempts, world.faultLog().partitionActive(created),
					world.faultLog().faultActive(created),
					s.executed == null ? List.of()
							: s.executed.parts.stream().map(p -> p.target.decl().name()).toList()));
		}
		return out;
	}
}
