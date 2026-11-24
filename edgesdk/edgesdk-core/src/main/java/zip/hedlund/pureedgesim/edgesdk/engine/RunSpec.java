package zip.hedlund.pureedgesim.edgesdk.engine;

import java.nio.file.Path;
import java.util.Map;

import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;

/**
 * One run: a scenario, a candidate, and the seeds.
 *
 * @param runId         unique id within the experiment
 * @param scenario      the scenario (its fault model is the condition)
 * @param candidate     the algorithms under test
 * @param condition     label of the fault model
 * @param params        swept parameter values
 * @param replication   replication index
 * @param workloadSeed  tasks, storage operations, mobility
 * @param faultSeed     fault model
 * @param algorithmSeed candidate's random stream and packet loss draws
 * @param coreOutput    folder for PureEdgeSim's own files
 */
public record RunSpec(String runId, Scenario scenario, Candidate candidate, String condition,
		Map<String, String> params, int replication, long workloadSeed, long faultSeed, long algorithmSeed,
		Path coreOutput) {
}
