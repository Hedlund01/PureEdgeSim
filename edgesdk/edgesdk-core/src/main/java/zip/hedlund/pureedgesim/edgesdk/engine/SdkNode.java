package zip.hedlund.pureedgesim.edgesdk.engine;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.datacentersmanager.DefaultComputingNode;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.Task;

import zip.hedlund.pureedgesim.edgesdk.model.NodeDecl;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Position;

/**
 * A PureEdgeSim computing node that can crash and recover.
 * <p>
 * A crash increments the node's epoch, empties its queue and frees its cores;
 * tasks that were running finish into the void (their completion event carries an
 * older epoch and is ignored). The PureEdgeSim access-point logic
 * ({@code connectWith}) is disabled: the SDK routes over explicit links.
 */
public class SdkNode extends DefaultComputingNode {

	private final NodeDecl decl;
	private final SdkWorld world;
	private int epoch;
	private int crashHolds;
	private boolean permanentlyDown;

	public SdkNode(SimulationManager simulationManager, NodeDecl decl, SdkWorld world) {
		super(simulationManager, decl.spec().compute().mipsPerCore(), decl.spec().compute().cores(),
				decl.spec().memory().storageMb(), decl.spec().memory().ramMb());
		this.decl = decl;
		this.world = world;
		setName(decl.name());
		setType(decl.tier().coreType());
	}

	public NodeDecl decl() {
		return decl;
	}

	public NodeRef ref() {
		return decl.ref();
	}

	public boolean relays() {
		return decl.spec().relays();
	}

	/** @return the crash epoch; it changes at every crash */
	public int epoch() {
		return epoch;
	}

	/** @return true when the node runs (not crashed, battery not empty) */
	public boolean isUp() {
		return !isDead();
	}

	@Override
	public boolean isDead() {
		return super.isDead() || crashHolds > 0 || permanentlyDown;
	}

	public boolean isPermanentlyDown() {
		return permanentlyDown || super.isDead();
	}

	/** @return the current position */
	public Position position() {
		var l = getMobilityModel().getCurrentLocation();
		return new Position(l.getXPos(), l.getYPos());
	}

	public double distanceTo(SdkNode other) {
		return position().distanceTo(other.position());
	}

	public SdkEnergyModel sdkEnergy() {
		return (SdkEnergyModel) getEnergyModel();
	}

	public int busyCores() {
		return (int) getNumberOfCPUCores() - availableCores;
	}

	public int queued() {
		return getTasksQueue().size();
	}

	/** @return true when this call took the node down in the SDK (it was not crashed before) */
	boolean crash(boolean permanent) {
		boolean wasUp = crashHolds == 0 && !permanentlyDown;
		if (permanent)
			permanentlyDown = true;
		else
			crashHolds++;
		if (wasUp) {
			epoch++;
			getTasksQueue().clear();
			availableCores = (int) getNumberOfCPUCores();
			setAvailableRam(getRamCapacity());
			setAvailableStorage(getTotalStorage());
			tasks = 0;
			setIdle(true);
		}
		return wasUp;
	}

	/** @return true when this call brought the node back up */
	boolean recover() {
		if (crashHolds > 0)
			crashHolds--;
		return isUp();
	}

	@Override
	public void submitTask(Task task) {
		((SdkTask) task).epoch = epoch;
		super.submitTask(task);
	}

	@Override
	protected void executionFinished(Event e) {
		if (((SdkTask) e.getData()).epoch != epoch)
			return; // the node crashed meanwhile: the work is gone
		super.executionFinished(e);
	}

	@Override
	protected void updateStatus() {
		if (!isDead()) {
			super.updateStatus();
			return;
		}
		// A crashed vehicle keeps moving; it just does not compute.
		if (getMobilityModel().isMobile())
			getMobilityModel().updateLocation(simulationManager.getSimulation().clock());
	}

	@Override
	protected void setDeath(Boolean dead, double time) {
		boolean was = super.isDead();
		super.setDeath(dead, time);
		if (dead && !was)
			world.guard(() -> world.onBatteryDeath(this));
	}

	@Override
	protected void connectWith(ComputingNode closestEdgeDataCenter) {
		// The SDK routes over explicit links; no implicit access point.
	}

	@Override
	public String toString() {
		return decl.name();
	}
}
