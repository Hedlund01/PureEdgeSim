package zip.hedlund.pureedgesim.edgesdk.engine;

import java.util.concurrent.locks.ReentrantLock;

import zip.hedlund.pureedgesim.edgesdk.metrics.RunData;

/**
 * Hands a run's spec to the PureEdgeSim classes, which are created by reflection
 * with fixed constructors, and collects the result. PureEdgeSim keeps its
 * configuration in static fields, so only one run can be active per JVM: the
 * context holds a global lock while open.
 */
public final class RunContext {

	private static final ReentrantLock LOCK = new ReentrantLock();
	private static RunContext current;

	private final RunSpec spec;
	private RunData result;
	private Throwable failure;
	private boolean completed;

	private RunContext(RunSpec spec) {
		this.spec = spec;
	}

	/** Opens the context of a run; blocks while another run is active. */
	public static RunContext open(RunSpec spec) {
		LOCK.lock();
		current = new RunContext(spec);
		return current;
	}

	/** @return the active context */
	public static RunContext current() {
		if (current == null)
			throw new IllegalStateException("no SDK run is active");
		return current;
	}

	public void close() {
		current = null;
		LOCK.unlock();
	}

	public RunSpec spec() {
		return spec;
	}

	void fail(Throwable t) {
		if (failure == null)
			failure = t;
	}

	public Throwable failure() {
		return failure;
	}

	void complete(RunData data) {
		result = data;
		completed = data != null;
	}

	public boolean completed() {
		return completed;
	}

	public RunData result() {
		return result;
	}
}
