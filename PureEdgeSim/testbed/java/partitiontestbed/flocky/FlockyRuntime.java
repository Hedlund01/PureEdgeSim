package partitiontestbed.flocky;

import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationengine.OnSimulationStartListener;
import com.mechalikh.pureedgesim.simulationengine.SimEntity;

import partitiontestbed.TestbedWorld;
import partitiontestbed.flocky.Oam.Definition;

/**
 * Runs the periodic part of Flocky: the discovery and repository rounds of every
 * server, the evolution step of Swirly, and the leases of orphaned instances.
 * <p>
 * The servers are not synchronised: each one starts its rounds at a random
 * offset within the first interval, as independently booted services would. A
 * round runs in one event, on the topology as it is at that instant; the few
 * round trip times it costs are far below the interval between rounds and the
 * duration of a partition, so they are counted as traffic but not as time.
 *
 * @see FlockyAgent
 */
public class FlockyRuntime extends SimEntity implements OnSimulationStartListener {

	private static final int DISCOVERY_ROUND = 1;
	private static final int REPOSITORY_ROUND = 2;
	private static final int ORPHAN_LEASE_EXPIRED = 3;

	private final FlockyStrategy flocky;

	FlockyRuntime(FlockyStrategy flocky) {
		super(flocky.world.simulation());
		this.flocky = flocky;
	}

	@Override
	public void onSimulationStart() {
		for (FlockyAgent agent : flocky.agents()) {
			schedule(this, flocky.random.nextDouble() * flocky.config.discoveryInterval, DISCOVERY_ROUND, agent);
			schedule(this, flocky.random.nextDouble() * flocky.config.repositoryInterval, REPOSITORY_ROUND, agent);
		}
	}

	@Override
	protected void processEvent(Event e) {
		switch (e.getTag()) {
		case DISCOVERY_ROUND:
			FlockyAgent discovering = (FlockyAgent) e.getData();
			discovering.discover();
			if (flocky.config.proactiveMigration)
				flocky.checkPlacements(discovering);
			schedule(this, flocky.config.discoveryInterval, DISCOVERY_ROUND, discovering);
			break;
		case REPOSITORY_ROUND:
			FlockyAgent syncing = (FlockyAgent) e.getData();
			syncing.syncMetadata();
			schedule(this, flocky.config.repositoryInterval, REPOSITORY_ROUND, syncing);
			break;
		case ORPHAN_LEASE_EXPIRED:
			TestbedWorld.Placement orphan = (TestbedWorld.Placement) e.getData();
			TestbedWorld.Placement current = flocky.world.placementOf(orphan.device);
			// The device may have come back to the same instance in the meantime.
			if (current == null || current.host != orphan.host || current.detail != orphan.detail)
				flocky.agentOf(orphan.host).release((Definition) orphan.detail, orphan.device.getId());
			break;
		default:
			break;
		}
	}

	/**
	 * Frees an instance its client could not reach to release, once its lease
	 * runs out.
	 *
	 * @param placement the placement that was abandoned
	 */
	void releaseOrphan(TestbedWorld.Placement placement) {
		schedule(this, flocky.config.orphanTimeout, ORPHAN_LEASE_EXPIRED, placement);
	}
}
