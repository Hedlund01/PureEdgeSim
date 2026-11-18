package partitiontestbed;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

/**
 * One run of a {@link Job} on one host, from the moment a strategy dispatches it.
 * A task can have several, in parallel or one after another. An attempt runs the
 * whole task, or one part of it when the strategy divided it
 * ({@link StrategyContext#divide}).
 */
public final class Attempt {

	/** Where an attempt is. */
	public enum State {
		/** On its way to its host. */
		SENT,
		/** At its host, waiting for its instance to start or for a free core. */
		WAITING,
		/** Running. */
		RUNNING,
		/** Ran; its result is the strategy's to send or finish. */
		RAN,
		/** Lost: on the way, at a host without instance, or with a host that crashed. */
		LOST,
		/** Cancelled by the strategy. */
		CANCELLED
	}

	private static int counter;

	private final int id = ++counter;
	private final Job job;
	private final ComputingNode from;
	private final ComputingNode host;
	private final int part;
	State state = State.SENT;
	double ranAt = Double.NaN;

	Attempt(Job job, ComputingNode from, ComputingNode host) {
		this(job, from, host, WHOLE);
	}

	Attempt(Job job, ComputingNode from, ComputingNode host, int part) {
		this.job = job;
		this.from = from;
		this.host = host;
		this.part = part;
	}

	/** The part of an attempt that runs the whole task. */
	public static final int WHOLE = -1;

	/** @return the part of its task it runs, or {@link #WHOLE} */
	public int part() {
		return part;
	}

	/** @return the share of its task it runs: 1 for the whole task */
	public double share() {
		return part == WHOLE ? 1 : job.share(part);
	}

	/** @return a number that identifies it in the run */
	public int id() {
		return id;
	}

	/** @return its task */
	public Job job() {
		return job;
	}

	/** @return the node that dispatched it */
	public ComputingNode from() {
		return from;
	}

	/** @return the node that runs it */
	public ComputingNode host() {
		return host;
	}

	/** @return where it is */
	public State state() {
		return state;
	}

	/** @return when it finished running, or NaN */
	public double ranAt() {
		return ranAt;
	}

	@Override
	public String toString() {
		return "attempt " + id + " of " + job + (part == WHOLE ? "" : " (part " + part + ")") + " on " + host.getName();
	}
}
