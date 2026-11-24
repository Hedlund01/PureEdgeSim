package zip.hedlund.pureedgesim.edgesdk.engine;

import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationengine.PureEdgeSim;
import com.mechalikh.pureedgesim.simulationengine.SimEntity;

import zip.hedlund.pureedgesim.edgesdk.algorithm.Cancellable;

/**
 * Runs SDK actions on the PureEdgeSim event queue. Actions scheduled for the same
 * time run in the order they were scheduled.
 */
public final class Scheduler extends SimEntity {

	private static final int RUN = 1;

	private final SdkWorld world;

	Scheduler(PureEdgeSim simulation, SdkWorld world) {
		super(simulation);
		this.world = world;
	}

	/** Runs {@code action} after {@code delay} seconds. */
	public Action after(double delay, Runnable action) {
		Action a = new Action(action);
		schedule(this, Math.max(0, delay), RUN, a);
		return a;
	}

	@Override
	protected void processEvent(Event e) {
		if (e.getTag() == RUN) {
			Action a = (Action) e.getData();
			if (!a.cancelled)
				world.guard(a.action);
		}
	}

	/** A scheduled action. */
	public static final class Action implements Cancellable {
		private final Runnable action;
		private boolean cancelled;

		Action(Runnable action) {
			this.action = action;
		}

		@Override
		public void cancel() {
			cancelled = true;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}
	}
}
