package zip.hedlund.pureedgesim.edgesdk.examples;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.HeartbeatFallback;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.NearestByDesign;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky.Component;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky.ComponentDefinition;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky.Flocky;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky.QoeEvaluator;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Experiment;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.metrics.Direction;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;

/**
 * Flocky (decentralized, intent-based OAM orchestration) against the built-in
 * policies. The video component prefers green energy; it has a container
 * implementation and a secure unikernel one that only the micro data center can
 * run. Each phone discovers its neighbourhood, learns the nodes' traits and
 * resources through the Repository, deploys the component on the best node and
 * migrates it when that node stops answering.
 */
public final class Story9Flocky {

	static Flocky flocky(QoeEvaluator evaluator) {
		return new Flocky().component(Component.of("video").prefers("green"))
				.definition(ComponentDefinition.of("video-container", "video", 4, 2_048))
				.definition(ComponentDefinition.of("video-unikernel", "video", 2, 512).withWorkloadType("unikernel")
						.provides("secure-runtime"), Select.name("mdc"))
				.traits(Select.name("rsu-2"), "green").workloadTypes(Select.name("mdc"), "unikernel")
				.evaluator(evaluator);
	}

	public static void main(String[] args) {
		var scenario = Story5CompareFaultModels.city().toBuilder().withFaults(FaultScript.parse("""
				120s partition district-1 for 90s
				300s crash rsu-2 recover 30s
				""")).build();

		Experiment.on(scenario).candidate(Candidate.of("nearest", NearestByDesign::new))
				.candidate(Candidate.of("heartbeat", HeartbeatFallback::new))
				.candidate(Candidate.of("flocky-legacy", () -> flocky(QoeEvaluator.LEGACY)))
				.candidate(Candidate.of("flocky-scored", () -> flocky(QoeEvaluator.scored())))
				.baseline("nearest").replications(Examples.replications(3))
				.metrics(Metrics.TASK_SUCCESS, Metrics.SUCCESS_DURING_PARTITION, Metrics.LATENCY_P95,
						Metrics.CONTROL_OVERHEAD, Metrics.TIME_TO_RECOVER,
						Metrics.custom("flocky.migrations", Direction.LOWER_IS_BETTER),
						Metrics.custom("flocky.known_nodes.mean"))
				.run(Examples.output("story9"));
	}
}
