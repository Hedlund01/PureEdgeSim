package zip.hedlund.pureedgesim.edgesdk.engine;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.network.NetworkModel;
import com.mechalikh.pureedgesim.network.TransferProgress;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;

/**
 * Placeholder network model: in an SDK run every transfer goes through
 * {@link zip.hedlund.pureedgesim.edgesdk.network.NetworkEngine}, which routes over
 * the live topology. The core transfer path is not used.
 */
public class SdkNetworkModel extends NetworkModel {

	public SdkNetworkModel(SimulationManager simulationManager) {
		super(simulationManager);
	}

	@Override
	protected void processEvent(Event e) {
		// unused
	}

	@Override
	protected void updateEdgeDevicesRemainingEnergy(TransferProgress transfer, ComputingNode origin,
			ComputingNode destination) {
		// energy is charged per hop by the SDK
	}

	@Override
	protected void transferFinished(TransferProgress transfer) {
		// unused
	}
}
