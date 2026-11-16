package partitiontestbed;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.taskgenerator.Task;

/**
 * Two variations of {@link AccessServerStrategy} that use what a strategy may do
 * beyond placing a service: decide for every task, and decide what a failed task
 * does to the placement.
 */
final class RoutingStrategies {

	/** Places every service on the access server, and sends every task to the cloud. */
	static final String TO_CLOUD = "ROUTE_TO_CLOUD";
	/** Keeps a placement whatever happens to its tasks. */
	static final String KEEP = "KEEP_PLACEMENT";

	private RoutingStrategies() {
	}

	static void register() {
		Testbed.register(TO_CLOUD, ToCloud::new);
		Testbed.register(KEEP, Keep::new);
	}

	private static final class ToCloud extends AccessServerStrategy {
		private TestbedWorld world;

		@Override
		public void initialize(TestbedWorld world) {
			this.world = world;
		}

		@Override
		public ComputingNode route(Task task, TestbedWorld.Placement placement) {
			return world.cloud();
		}
	}

	private static final class Keep extends AccessServerStrategy {
		@Override
		public boolean taskFailed(Task task, TestbedWorld.Placement placement) {
			return false;
		}
	}
}
