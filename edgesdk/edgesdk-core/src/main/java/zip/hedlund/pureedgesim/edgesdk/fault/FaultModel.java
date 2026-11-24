package zip.hedlund.pureedgesim.edgesdk.fault;

import java.util.Random;

import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;

/**
 * Decides which faults happen and when. Subclass it for a custom model.
 * <p>
 * {@link #install} is called once at time 0 with the model's own random stream,
 * which depends only on the scenario seed and the replication. Draw the faults
 * from it and from nothing else, and every algorithm compared will face exactly
 * the same faults.
 */
public abstract class FaultModel {

	/**
	 * Schedules the faults of a run.
	 *
	 * @param scheduler where to schedule them
	 * @param topology  the design topology, to resolve targets
	 * @param rng       this model's random stream
	 */
	public abstract void install(FaultScheduler scheduler, DesignTopology topology, Random rng);

	/** @return a short label used in reports, e.g. {@code "random-links"} */
	public String label() {
		return getClass().getSimpleName();
	}

	/** @return a model that installs this one and then {@code other} */
	public FaultModel and(FaultModel other) {
		return new CompositeFaultModel(this, other);
	}

	/** @return this model with another label */
	public FaultModel labelled(String newLabel) {
		FaultModel self = this;
		return new FaultModel() {
			@Override
			public void install(FaultScheduler scheduler, DesignTopology topology, Random rng) {
				self.install(scheduler, topology, rng);
			}

			@Override
			public String label() {
				return newLabel;
			}
		};
	}
}
