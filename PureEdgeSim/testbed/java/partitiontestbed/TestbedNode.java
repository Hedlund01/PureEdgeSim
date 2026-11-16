package partitiontestbed;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.datacentersmanager.DefaultComputingNode;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;

/**
 * A computing node whose links are the testbed's to manage.
 * <p>
 * The framework points the links of a device that moves at the edge data center
 * closest to it, from a table it computes before the run and that assumes the
 * edge data centers stay where they are. Here a device keeps its links, and the
 * {@link TestbedWorld} decides which access server it is attached to, from where
 * the device and the servers are now and from which servers still answer.
 *
 * @see TestbedWorld#updateAttachments()
 */
public class TestbedNode extends DefaultComputingNode {

	/**
	 * @param simulationManager the testbed simulation manager
	 * @param mipsPerCore       the capacity of one core, in MIPS
	 * @param numberOfCPUCores  the number of cores
	 * @param storage           the storage, in megabytes
	 * @param ram               the memory, in megabytes
	 */
	public TestbedNode(SimulationManager simulationManager, double mipsPerCore, int numberOfCPUCores, double storage,
			double ram) {
		super(simulationManager, mipsPerCore, numberOfCPUCores, storage, ram);
	}

	@Override
	protected void connectWith(ComputingNode closestEdgeDataCenter) {
		// The attachment follows the live topology, not the framework's table.
	}
}
