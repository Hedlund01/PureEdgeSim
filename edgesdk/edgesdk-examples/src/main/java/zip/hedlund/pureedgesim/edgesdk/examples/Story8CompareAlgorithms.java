package zip.hedlund.pureedgesim.edgesdk.examples;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.CloudOnly;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.HeartbeatFallback;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.LocalFirst;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.NearestByDesign;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Experiment;
import zip.hedlund.pureedgesim.edgesdk.api.ExperimentResult;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageWorkload;
import zip.hedlund.pureedgesim.edgesdk.storage.strategies.EventualGossip;
import zip.hedlund.pureedgesim.edgesdk.storage.strategies.PrimaryBackup;
import zip.hedlund.pureedgesim.edgesdk.storage.strategies.Quorum;

/**
 * Compare several algorithms under identical faults: the same workload, the
 * same mobility and the same fault timeline for every candidate, several
 * replications, and a comparison against a baseline.
 */
public final class Story8CompareAlgorithms {

	public static void main(String[] args) {
		var scenario = Story5CompareFaultModels.city().toBuilder()
				.withStorageWorkload(StorageWorkload.zipf(200, 0.9).reads(12).writes(4).timeout(1))
				.withFaults(FaultScript.parse("""
						120s partition district-1 for 90s
						300s crash mdc recover 30s
						""")).build();

		ExperimentResult result = Experiment.on(scenario)
				.candidate(Candidate.of("cloud-only", CloudOnly::new).storage(() -> new PrimaryBackup(2)))
				.candidate(Candidate.of("local-first", LocalFirst::new).storage(() -> new PrimaryBackup(2)))
				.candidate(Candidate.of("nearest", NearestByDesign::new).storage(() -> new Quorum(3, 2, 2)))
				.candidate(Candidate.of("heartbeat", HeartbeatFallback::new).storage(EventualGossip::new))
				.baseline("cloud-only").replications(Examples.replications(5))
				.metrics(Metrics.TASK_SUCCESS, Metrics.SUCCESS_DURING_PARTITION, Metrics.LATENCY_P95,
						Metrics.ENERGY_DEVICES, Metrics.CONTROL_OVERHEAD, Metrics.TIME_TO_RECOVER,
						Metrics.STORAGE_AVAILABILITY, Metrics.STALE_READS, Metrics.STORAGE_CONVERGENCE)
				.run(Examples.output("story8"));

		System.out.println("best task success during the partition: "
				+ result.best(Metrics.SUCCESS_DURING_PARTITION).orElse("-"));
	}
}
