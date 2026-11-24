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
 * PureEdgeSim node, returned as a result. The first result to reach the device
 * wins; the deadline is checked at the device.
 */
final class TaskEngine {

	/** One placement of a task. */
	static final class Attempt {
		final State state;
		final SdkNode target;
		final int number;

		Attempt(State state, SdkNode target, int number) {
			this.state = state;
			this.target = target;
			this.number = number;
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
		/** The result arrived after the deadline event had already counted the task as failed. */
		boolean late;
		TaskOutcome lastLoss;
		SdkNode executedOn;
		double completedAt = Double.NaN;

		State(TaskRequest request, WorkloadSpec workload) {
			this.request = request;
			this.workload = workload;
		}

		boolean hasResult() {
			return !Double.isNaN(completedAt);
		}
	}

	private final SdkWorld world;
	private final AlgorithmRuntime runtime;
	private final OrchestrationPolicy policy;
	private final List<State> states = new ArrayList<>();
	private final Map<SdkNode, Set<Attempt>> executing = new HashMap<>();
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
		SdkNode target = p.isLocal() ? device : world.node(p.node().get());
		Attempt a = new Attempt(s, target, ++s.attempts);
		if (p.retryAfter() > 0)
			runtime.schedule(device.ref(), p.retryAfter(), () -> {
				if (!s.hasResult() && !s.deadlinePassed && device.epoch() == s.deviceEpoch)
					attempt(s, policy.onAttemptTimedOut(device.ref(), s.request, target.ref(), s.attempts));
			});
		if (target == device) {
			execute(a);
			return;
		}
		world.network().send(device, target, s.request.inputBytes(), Transfer.Kind.TASK_INPUT, t -> execute(a),
				(t, reason) -> s.lastLoss = map(reason));
	}

	private void execute(Attempt a) {
		if (!a.target.isUp()) {
			a.state.lastLoss = TaskOutcome.NODE_CRASHED;
			return;
		}
		SdkTask task = new SdkTask(nextCoreTaskId++, a);
		task.setLength(a.state.request.lengthMi());
		task.setContainerSizeInBits((long) (a.state.workload.containerMb() * 8_000_000L));
		task.setFileSizeInBits(a.state.request.inputBytes() * 8);
		task.setOutputSizeInBits(a.state.request.outputBytes() * 8);
		task.setMaxLatency(a.state.workload.deadlineS());
		task.setEdgeDevice(world.node(a.state.request.device()));
		task.setOffloadingDestination(a.target);
		executing.computeIfAbsent(a.target, k -> new LinkedHashSet<>()).add(a);
		a.target.submitTask(task);
	}

	/** PureEdgeSim finished executing a task. */
	void onExecuted(SdkTask task) {
		Attempt a = task.attempt;
		Set<Attempt> running = executing.get(a.target);
		if (running == null || !running.remove(a))
			return; // the node crashed in between
		SdkNode device = world.node(a.state.request.device());
		if (a.target == device) {
			resultArrived(a);
			return;
		}
		world.network().send(a.target, device, a.state.request.outputBytes(), Transfer.Kind.TASK_RESULT, t -> {
			if (device.epoch() == a.state.deviceEpoch)
				resultArrived(a);
			else
				a.state.lastLoss = TaskOutcome.DEVICE_DOWN;
		}, (t, reason) -> a.state.lastLoss = map(reason));
	}

	private void resultArrived(Attempt a) {
		State s = a.state;
		if (s.hasResult())
			return;
		s.completedAt = world.now();
		s.executedOn = a.target;
		// deadline() already counted the task as failed; also when both fall at the same instant
		s.late = s.deadlinePassed;
		if (!s.late) {
			doneInWindow++;
			succeeded++;
			finished++;
		}
		policy.onTaskCompleted(s.request.device(),
				new TaskReport(s.request, a.target.ref(), s.completedAt, s.attempts, s.late));
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
		Set<Attempt> running = executing.remove(node);
		if (running != null)
			for (Attempt a : running)
				a.state.lastLoss = TaskOutcome.NODE_CRASHED;
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
	 * without a result (failure). A result that arrives once the deadline has
	 * passed, including at the same instant after the deadline was checked, does
	 * not change that: the task stays a failure, recorded as
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
			else if (s.lastLoss != null)
				outcome = s.lastLoss;
			else
				outcome = TaskOutcome.TIMED_OUT;
			double created = s.request.createdAt();
			out.add(new TaskRecord(s.request.id(), s.request.device().name(), s.request.workload(), created,
					s.request.deadline(), outcome, s.executedOn == null ? "" : s.executedOn.decl().name(),
					s.completedAt, s.attempts, world.faultLog().partitionActive(created),
					world.faultLog().faultActive(created)));
		}
		return out;
	}
}
