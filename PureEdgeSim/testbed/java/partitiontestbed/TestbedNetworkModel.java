package partitiontestbed;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

import org.jgrapht.GraphPath;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.network.DefaultNetworkModel;
import com.mechalikh.pureedgesim.network.NetworkLink;
import com.mechalikh.pureedgesim.network.TransferProgress;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.Task;

/**
 * Routes every transfer over the links that are up at the moment it is sent,
 * and loses it when one of them goes down before it arrives.
 * <p>
 * The default network model routes over the paths computed when the topology
 * was built, and assumes they exist. Here a transfer is routed on the live
 * topology instead: it takes the detour a failure leaves, and it fails at once,
 * with {@link Task.FailureReason#FAILED_DUE_TO_NETWORK_OUTAGE}, when there is no
 * path at all. A transfer already on its way when a link it still has to cross
 * goes down is failed when it would have arrived, as a connection that is never
 * acknowledged; a link it has already left can go down without harm.
 * <p>
 * A request lost before it ran is failed in phase 1 of the framework, a result
 * lost on its way back in phase 3, so that the framework releases what the task
 * held at its destination.
 *
 * @see LiveTopology
 */
public class TestbedNetworkModel extends DefaultNetworkModel {

	/** Transfers in flight, and whether a link went down ahead of each. */
	private final Map<TransferProgress, Boolean> flights = new IdentityHashMap<>();
	/** Transfers lost to a link that was down, for the report. */
	int lostTransfers;
	private boolean listening;

	/**
	 * @param simulationManager the testbed simulation manager
	 */
	public TestbedNetworkModel(SimulationManager simulationManager) {
		super(simulationManager);
	}

	private TestbedWorld world() {
		return ((TestbedSimulationManager) simulationManager).getWorld();
	}

	@Override
	public void send(ComputingNode from, ComputingNode to, Task task, double fileSize, TransferProgress.Type type) {
		LiveTopology topology = world().topology();
		if (!listening) {
			topology.onLoss(this::markLostFlights);
			listening = true;
		}
		GraphPath<ComputingNode, NetworkLink> path = topology.path(from, to);
		if (path == null) {
			lose(task, type);
			return;
		}
		TransferProgress transfer = new TransferProgress(task, fileSize, type)
				.setVertexList(new ArrayList<>(path.getVertexList()))
				.setEdgeList(new ArrayList<>(path.getEdgeList()));
		if (path.getEdgeList().isEmpty()) {
			scheduleNow(this, TRANSFER_FINISHED, transfer);
			return;
		}
		flights.put(transfer, false);
		path.getEdgeList().get(0).addTransfer(transfer);
	}

	/**
	 * Sends a task from the host of the stage before it, on the edge, straight to
	 * its own host: the device is not on the way.
	 *
	 * @param from the node that holds the input of the task
	 * @param to   the node that will run it
	 * @param task the task
	 */
	void sendBetweenHosts(ComputingNode from, ComputingNode to, Task task) {
		if (from != to)
			send(from, to, task, task.getFileSizeInBits(), TransferProgress.Type.TASK);
		else
			executeTaskOrDownloadContainer(
					new TransferProgress(task, task.getFileSizeInBits(), TransferProgress.Type.TASK));
	}

	/** Marks the transfers that still had to cross a link that just went down. */
	private void markLostFlights() {
		LiveTopology topology = world().topology();
		for (Map.Entry<TransferProgress, Boolean> flight : flights.entrySet()) {
			if (flight.getValue())
				continue;
			// The links a transfer has left are no longer in its list.
			for (NetworkLink link : flight.getKey().getEdgeList())
				if (!topology.carries(link)) {
					flight.setValue(true);
					break;
				}
		}
	}

	@Override
	protected void transferFinished(TransferProgress transfer) {
		Boolean lost = flights.remove(transfer);
		if (lost != null && lost) {
			lose(transfer.getTask(), transfer.getTransferType());
			return;
		}
		super.transferFinished(transfer);
	}

	private void lose(Task task, TransferProgress.Type type) {
		lostTransfers++;
		boolean executed = type == TransferProgress.Type.RESULTS_TO_ORCH
				|| type == TransferProgress.Type.RESULTS_TO_DEV;
		((TestbedSimulationManager) simulationManager).failOnNetwork(task, executed ? 3 : 1);
	}
}
