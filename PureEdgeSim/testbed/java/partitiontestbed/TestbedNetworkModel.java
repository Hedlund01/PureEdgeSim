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
 * Moves the {@link Carrier}s of the testbed over the links that are up at the
 * moment they are sent, and loses them when one of those links goes down before
 * they arrive.
 * <p>
 * The default network model routes over the paths computed when the topology was
 * built, and assumes they exist. Here a carrier is routed on the live topology: it
 * takes the detour a failure leaves, and is lost at once when there is no path at
 * all. A carrier already on its way when a link it still has to cross goes down is
 * lost when it would have arrived, as a connection that is never acknowledged; a
 * link it has already left can go down without harm. Nobody is told of a loss:
 * the sender finds out, if at all, by its own timeouts.
 * <p>
 * Every link is shared by everything on it: the tasks, the results and the
 * strategy's own messages.
 *
 * @see LiveTopology
 * @see TaskEngine
 */
public class TestbedNetworkModel extends DefaultNetworkModel {

	/** Carriers in flight, and whether a link went down ahead of each. */
	private final Map<TransferProgress, Boolean> flights = new IdentityHashMap<>();
	/** Carriers lost to a link that was down, for the report. */
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

	/** Puts a carrier on the live topology, or loses it when there is no path. */
	void send(Carrier carrier) {
		LiveTopology topology = world().topology();
		if (!listening) {
			topology.onLoss(this::markLostFlights);
			listening = true;
		}
		GraphPath<ComputingNode, NetworkLink> path = topology.path(carrier.from, carrier.to);
		if (path == null) {
			lostTransfers++;
			world().engine.lost(carrier);
			return;
		}
		TransferProgress transfer = new TransferProgress(carrier, carrier.bytes * 8.0, TransferProgress.Type.REQUEST)
				.setVertexList(new ArrayList<>(path.getVertexList()))
				.setEdgeList(new ArrayList<>(path.getEdgeList()));
		if (path.getEdgeList().isEmpty()) {
			scheduleNow(this, TRANSFER_FINISHED, transfer);
			return;
		}
		flights.put(transfer, false);
		path.getEdgeList().get(0).addTransfer(transfer);
	}

	@Override
	public void send(ComputingNode from, ComputingNode to, Task task, double fileSize, TransferProgress.Type type) {
		throw new IllegalStateException("TestbedNetworkModel - everything on the network is a carrier of the testbed");
	}

	/** Marks the carriers that still had to cross a link that just left the routes. */
	private void markLostFlights() {
		LiveTopology topology = world().topology();
		for (Map.Entry<TransferProgress, Boolean> flight : flights.entrySet()) {
			if (flight.getValue())
				continue;
			// The links a transfer has left are no longer in its list.
			for (NetworkLink link : flight.getKey().getEdgeList())
				if (!topology.routable(link)) {
					flight.setValue(true);
					break;
				}
		}
	}

	@Override
	protected void transferFinished(TransferProgress transfer) {
		Boolean lost = flights.remove(transfer);
		Carrier carrier = (Carrier) transfer.getTask();
		if (lost != null && lost) {
			lostTransfers++;
			world().engine.lost(carrier);
			return;
		}
		world().engine.arrived(carrier);
	}
}
