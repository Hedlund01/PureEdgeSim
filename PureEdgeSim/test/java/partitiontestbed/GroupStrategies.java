package partitiontestbed;

import java.util.List;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

import partitiontestbed.baselines.AccessServerStrategy;

/**
 * Two variations of {@link AccessServerStrategy} for the group transfers of a
 * partition: one admits every node and checks, at every row of the timeline,
 * that no path joins two servers of different groups as they are now; the other
 * admits none. The check reads the testbed itself, which a strategy under test
 * never does.
 */
final class GroupStrategies {

	/** Admits every node, and counts the paths that cross between two groups. */
	static final String OPEN = "OPEN_GROUPS";
	/** Admits no node into another group. */
	static final String CLOSED = "CLOSED_GROUPS";

	/** Paths found between two servers of different groups, by the last run of {@link #OPEN}. */
	static int crossings;
	/** Times {@link #OPEN} was asked to admit a node, in its last run. */
	static int asked;

	private GroupStrategies() {
	}

	static void register() {
		Testbed.register(OPEN, Open::new);
		Testbed.register(CLOSED, Closed::new);
	}

	private static final class Open extends AccessServerStrategy {
		@Override
		public void initialize() {
			crossings = 0;
			asked = 0;
		}

		@Override
		public boolean admitJoin(ComputingNode node, EdgeServer contact) {
			asked++;
			return true;
		}

		@Override
		public List<String> timelineValues() {
			TestbedWorld world = ((TaskEngine) ctx).world();
			List<EdgeServer> servers = world.servers();
			for (TestbedWorld.ActivePartition partition : world.activePartitions())
				for (EdgeServer a : servers)
					for (EdgeServer b : servers)
						if (!partition.groupOf.get(a).equals(partition.groupOf.get(b))
								&& world.topology().reachable(a.node, b.node))
							crossings++;
			return List.of();
		}
	}

	private static final class Closed extends AccessServerStrategy {
		@Override
		public boolean admitJoin(ComputingNode node, EdgeServer contact) {
			return false;
		}
	}
}
