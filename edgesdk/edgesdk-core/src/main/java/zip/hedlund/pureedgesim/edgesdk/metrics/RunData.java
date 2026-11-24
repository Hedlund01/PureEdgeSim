package zip.hedlund.pureedgesim.edgesdk.metrics;

import java.util.List;
import java.util.Map;

import zip.hedlund.pureedgesim.edgesdk.fault.FaultLog;

/**
 * Everything recorded about one run: the input to every {@link Metric}.
 *
 * @param runId         unique id of the run within an experiment
 * @param scenario      scenario name
 * @param candidate     algorithm candidate name
 * @param condition     fault model label
 * @param params        swept parameter values
 * @param replication   replication index
 * @param workloadSeed  seed of tasks, storage operations and mobility
 * @param faultSeed     seed of the fault model
 * @param algorithmSeed seed of the candidate's random stream
 * @param duration      simulated seconds
 * @param tasks         every task
 * @param storage       every storage operation
 * @param faults        ground-truth faults
 * @param declarations  beliefs declared by the algorithms
 * @param timeline      periodic samples
 * @param energy        energy per node
 * @param controlBytes  bytes of orchestration-algorithm messages
 * @param storageBytes  bytes of storage-strategy messages
 * @param taskBytes     bytes of task inputs and results
 * @param transfersLost transfers lost, by reason
 * @param custom        custom counters and sample means
 * @param nodeCount     number of nodes
 */
public record RunData(String runId, String scenario, String candidate, String condition, Map<String, String> params,
		int replication, long workloadSeed, long faultSeed, long algorithmSeed, double duration, List<TaskRecord> tasks,
		List<StorageRecord> storage, FaultLog faults, List<DeclarationRecord> declarations,
		List<TimelineSample> timeline, List<NodeEnergy> energy, long controlBytes, long storageBytes, long taskBytes,
		Map<String, Long> transfersLost, Map<String, Double> custom, int nodeCount) {

	public RunData {
		params = Map.copyOf(params);
		tasks = List.copyOf(tasks);
		storage = List.copyOf(storage);
		declarations = List.copyOf(declarations);
		timeline = List.copyOf(timeline);
		energy = List.copyOf(energy);
		transfersLost = Map.copyOf(transfersLost);
		custom = Map.copyOf(custom);
	}
}
