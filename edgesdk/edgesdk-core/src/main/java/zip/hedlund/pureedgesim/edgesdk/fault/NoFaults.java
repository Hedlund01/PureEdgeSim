package zip.hedlund.pureedgesim.edgesdk.fault;

import java.util.Random;

import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;

/**
 * No injected faults. Links can still go out of range when nodes move, unless
 * the scenario disables emergent faults.
 */
public final class NoFaults extends FaultModel {

	public static final NoFaults INSTANCE = new NoFaults();

	private NoFaults() {
	}

	@Override
	public void install(FaultScheduler scheduler, DesignTopology topology, Random rng) {
		// nothing to inject
	}

	@Override
	public String label() {
		return "none";
	}
}
