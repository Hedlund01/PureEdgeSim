package partitiontestbed;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

/**
 * The simplest strategy there is: every service runs on the access server of the
 * device that calls it, at once, with no coordination. It tests the testbed on
 * its own, apart from Flocky, and shows what a strategy has to implement.
 */
public class AccessServerStrategy implements PlacementStrategy {

	/** The name it is registered under. */
	public static final String NAME = "ACCESS_SERVER";

	/** Registers it with the testbed, once. */
	public static void register() {
		Testbed.register(NAME, AccessServerStrategy::new);
	}

	@Override
	public void initialize(TestbedWorld world) {
		// Nothing to set up: it needs to know nothing about the other servers.
	}

	@Override
	public Deployment deploy(ComputingNode device, EdgeServer home, Service service) {
		Deployment deployment = new Deployment(device, home, service);
		deployment.host = home;
		deployment.outcome = "LOCAL";
		return deployment;
	}

	@Override
	public void release(TestbedWorld.Placement placement) {
		// It reserves nothing.
	}
}
