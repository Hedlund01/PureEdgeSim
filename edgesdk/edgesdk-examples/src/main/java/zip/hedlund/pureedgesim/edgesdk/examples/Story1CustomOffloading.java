package zip.hedlund.pureedgesim.edgesdk.examples;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zip.hedlund.pureedgesim.edgesdk.algorithm.NodeLocal;
import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskReport;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.NearestByDesign;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Experiment;
import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.CloudPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.EdgePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.WorkloadPresets;

/**
 * User story 1: implement a custom task-offloading algorithm (device → edge →
 * cloud) by subclassing {@link OrchestrationPolicy}, and compare it with a
 * built-in baseline.
 * <p>
 * {@code LatencyLearner} keeps, at every device, an estimate of each server's
 * response time learned from its own results, and a strike count of tasks that
 * timed out. It offloads to the server with the best estimate, skips servers with
 * recent strikes, and falls back to local execution. It never sees faults: it
 * only learns from what comes back.
 */
public final class Story1CustomOffloading {

	/** The custom algorithm. */
	public static class LatencyLearner extends OrchestrationPolicy {

		private static final class Knowledge {
			final Map<NodeRef, Double> latency = new HashMap<>();
			final Map<NodeRef, Double> blockedUntil = new HashMap<>();
			final Map<Long, NodeRef> sentTo = new HashMap<>();
		}

		private NodeLocal<Knowledge> knowledge;
		private List<NodeRef> servers;

		@Override
		public void onStart(SdkContext ctx) {
			knowledge = ctx.nodeLocal(n -> new Knowledge());
			servers = new ArrayList<>(ctx.topology().edgeNodes());
			servers.addAll(ctx.topology().nodes(Tier.CLOUD));
		}

		@Override
		public Placement place(NodeRef device, TaskRequest task) {
			Knowledge k = knowledge.at(device);
			NodeRef best = null;
			double bestEstimate = Double.POSITIVE_INFINITY;
			for (NodeRef s : servers) {
				if (k.blockedUntil.getOrDefault(s, 0.0) > ctx.now())
					continue;
				// unknown servers are estimated from the design latency (round trip)
				double estimate = k.latency.getOrDefault(s, 2 * ctx.topology().designLatency(device, s));
				if (estimate < bestEstimate) {
					best = s;
					bestEstimate = estimate;
				}
			}
			if (best == null || bestEstimate > task.deadline() - task.createdAt())
				return Placement.local();
			k.sentTo.put(task.id(), best);
			return Placement.on(best);
		}

		@Override
		public void onTaskCompleted(NodeRef device, TaskReport report) {
			Knowledge k = knowledge.at(device);
			k.sentTo.remove(report.task().id());
			if (report.executedOn().equals(device))
				return;
			// exponentially weighted moving average of observed latency
			k.latency.merge(report.executedOn(), report.latency(), (old, now) -> 0.8 * old + 0.2 * now);
			ctx.metrics().record("observed_latency_s", report.latency());
		}

		@Override
		public void onTaskTimedOut(NodeRef device, TaskRequest task) {
			Knowledge k = knowledge.at(device);
			NodeRef server = k.sentTo.remove(task.id());
			if (server != null) {
				// avoid this server for a while (back-off), then probe it again
				k.blockedUntil.put(server, ctx.now() + 20);
				ctx.metrics().add("servers_blocked", 1);
			}
		}
	}

	public static void main(String[] args) {
		Scenario scenario = Scenario.builder("story1-offloading").duration(Examples.duration(300)).seed(11)
				.map(2_000, 2_000)
				.addEdgeNode("edge-1", EdgePresets.ROADSIDE_SERVER, Layout.at(600, 1_000))
				.addEdgeNode("edge-2", EdgePresets.MICRO_DC, Layout.at(1_400, 1_000))
				.addCloud("cloud", CloudPresets.REGIONAL_DC, Layout.at(1_000, 1_900))
				.addEndDevices(DevicePresets.SMARTPHONE, 10, Layout.near("edge-1", 300))
				.connectEach(Tier.END_DEVICE).toNearest(Tier.EDGE_STATIC).via(ChannelPresets.CELLULAR_5G)
				.connect(Select.tier(Tier.EDGE_STATIC), Select.name("cloud"), ChannelPresets.FIBER)
				.connect("edge-1", "edge-2", ChannelPresets.ETHERNET)
				.withWorkload(WorkloadPresets.VIDEO_ANALYTICS)
				// edge-1 fails for a minute: the learner must notice from its own timeouts
				.withFaults(FaultScript.parse("100s crash edge-1 recover 60s"))
				// phones walk; keep the run about the crash
				.emergentFaults(false).build();

		Experiment.on(scenario).candidate(Candidate.of("nearest", NearestByDesign::new))
				.candidate(Candidate.of("learner", LatencyLearner::new)).baseline("nearest")
				.replications(Examples.replications(3))
				.metrics(Metrics.TASK_SUCCESS, Metrics.SUCCESS_OUTSIDE_FAULTS, Metrics.LATENCY_MEAN,
						Metrics.LATENCY_P95, Metrics.TIME_TO_RECOVER, Metrics.custom("servers_blocked"))
				.run(Examples.output("story1"));
	}
}
