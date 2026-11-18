package partitiontestbed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.taskgenerator.Task;

/**
 * One task as the testbed judges it: created by a device, or released by the
 * stage before it, and done once, when its result first reaches the device, or,
 * for a task that returns nothing, when it first finishes on a host. However many
 * times a strategy sends or runs it, it counts once. A task that a strategy
 * divided into parts ({@link StrategyContext#divide}) is done when the last of
 * its parts is: every part has to come back, or finish, once.
 * <p>
 * Its outcome is decided at the end of the run, against the requirement of its
 * service:
 * <ul>
 * <li>{@link Outcome#ON_TIME}: done within its deadline;</li>
 * <li>{@link Outcome#LATE}: done after its deadline, within the delivery window
 * of its service;</li>
 * <li>{@link Outcome#LOST}: not done within the window;</li>
 * <li>{@link Outcome#CENSORED}: not done when the run ended, and created too late
 * for its window to have run out: not judged.</li>
 * </ul>
 * A {@code deadline} service needs {@code ON_TIME}; a {@code lossless} one is
 * satisfied by {@code LATE} too.
 */
public final class Job {

	/** How a task ended. */
	public enum Outcome {
		ON_TIME, LATE, LOST, CENSORED
	}

	/** Something that happened to the network around a task while it was not done. */
	public enum Exposure {
		/** Its device was, or went, out of range of every access server. */
		DEVICE_DETACHED,
		/** Its device attached to another access server. */
		HANDOVER,
		/** Its device attached to an access server of another partition group. */
		GROUP_CHANGE,
		/** A server that had one of its attempts failed. */
		HOST_FAILED,
		/** A partition started. */
		PARTITION_STARTED
	}

	private final Task task;
	private final ComputingNode device;
	private final Service service;
	private final double created;
	private double done = Double.NaN;
	final List<Attempt> attempts = new ArrayList<>();
	final Set<Exposure> exposures = EnumSet.noneOf(Exposure.class);
	/** Whether a partition was in progress when it was created. */
	final boolean partitioned;
	/** The group of its device's access server in each partition in progress at its creation. */
	final Map<TestbedWorld.ActivePartition, Integer> groups = new LinkedHashMap<>();
	/** Results or finishes after the first. */
	int duplicates;
	/** The attempt that made it done. */
	Attempt doneBy;
	/** The shares of its parts, when a strategy divided it, or {@code null}. */
	private double[] shares;
	/** Which of its parts are done. */
	private boolean[] partsDone;

	Job(Task task, ComputingNode device, Service service, double created, boolean partitioned) {
		this.task = task;
		this.device = device;
		this.service = service;
		this.created = created;
		this.partitioned = partitioned;
	}

	/** @return the task of the framework, with its sizes and its stage */
	Task task() {
		return task;
	}

	/** @return a number that identifies it in the run */
	public int id() {
		return task.getId();
	}

	/** @return the device it belongs to */
	public ComputingNode device() {
		return device;
	}

	/** @return its service */
	public Service service() {
		return service;
	}

	/** @return the name of its stage, or {@code null} when its application has one stage */
	public String stage() {
		return task.getSubtaskName();
	}

	/** @return the size of what is sent to run it, in bytes */
	public long inputBytes() {
		return (long) (task.getFileSizeInBits() / 8);
	}

	/** @return the size of its result, in bytes */
	public long outputBytes() {
		return (long) (task.getOutputSizeInBits() / 8);
	}

	/** @return its length, in millions of instructions */
	public double length() {
		return task.getLength();
	}

	/** @return when it was created, in seconds */
	public double createdAt() {
		return created;
	}

	/** @return the time it has to be done in, from its creation, in seconds */
	public double deadline() {
		return task.getMaxLatency();
	}

	/**
	 * @return the absolute time by which the request it belongs to must be done,
	 *         for a stage of a pipeline, or 0
	 */
	public double requestDeadline() {
		return task.getRequestDeadline();
	}

	/** @return whether it is done when its result reaches the device */
	public boolean returnsResult() {
		return service.returnsResults && !handsOverOnEdge();
	}

	/** @return whether it is a stage that follows another one of its request */
	public boolean followsStage() {
		return !task.getDependencies().isEmpty();
	}

	/** @return whether it is a stage of an edge pipeline with stages after it */
	public boolean handsOverOnEdge() {
		return service.edgePipeline && !task.getDependents().isEmpty();
	}

	/** @return whether a strategy divided it into parts */
	public boolean isDivided() {
		return shares != null;
	}

	/** @return the number of its parts: 1 when it is not divided */
	public int parts() {
		return shares == null ? 1 : shares.length;
	}

	/**
	 * @param part a part, from 0
	 * @return the share of the task it holds, of its length, its input and its
	 *         result
	 */
	public double share(int part) {
		return shares == null ? 1 : shares[part];
	}

	/**
	 * @param part a part, from 0
	 * @return whether it is done: its result reached the device, or it finished at
	 *         its host for a task that returns nothing
	 */
	public boolean isPartDone(int part) {
		return isDone() || partsDone != null && partsDone[part];
	}

	void divide(double[] normalized) {
		shares = normalized;
		partsDone = new boolean[normalized.length];
	}

	/**
	 * Marks the part of an attempt done.
	 *
	 * @return whether every part is now done, so the task is
	 */
	boolean partDone(Attempt attempt) {
		if (attempt.part() == Attempt.WHOLE)
			return true;
		partsDone[attempt.part()] = true;
		for (boolean partDone : partsDone)
			if (!partDone)
				return false;
		return true;
	}

	/** @return whether it is done */
	public boolean isDone() {
		return !Double.isNaN(done);
	}

	/** @return when it was done, or NaN */
	public double doneAt() {
		return done;
	}

	/** @return its attempts so far */
	public List<Attempt> attempts() {
		return Collections.unmodifiableList(attempts);
	}

	void done(double time, Attempt attempt) {
		done = time;
		doneBy = attempt;
	}

	/**
	 * @param end the time the run ended
	 * @return how it ended
	 */
	public Outcome outcome(double end) {
		double window = Math.max(service.deliveryWindow, deadline());
		if (isDone()) {
			double delay = done - created;
			boolean inTime = delay <= deadline() && (requestDeadline() <= 0 || done <= requestDeadline());
			if (inTime)
				return Outcome.ON_TIME;
			return delay <= window ? Outcome.LATE : Outcome.LOST;
		}
		return created + window <= end ? Outcome.LOST : Outcome.CENSORED;
	}

	/**
	 * @param end the time the run ended
	 * @return whether it met the requirement of its service; {@code null} when it
	 *         is censored
	 */
	public Boolean met(double end) {
		Outcome outcome = outcome(end);
		if (outcome == Outcome.CENSORED)
			return null;
		return outcome == Outcome.ON_TIME || service.lossless && outcome == Outcome.LATE;
	}

	@Override
	public String toString() {
		return "task " + id() + (stage() == null ? "" : " (" + stage() + ")") + " of " + device.getName();
	}
}
