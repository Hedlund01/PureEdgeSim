package zip.hedlund.pureedgesim.edgesdk.examples;

import java.util.List;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.HeartbeatFallback;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.NearestByDesign;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Experiment;
import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.fault.StochasticFaultModel;
import zip.hedlund.pureedgesim.edgesdk.fault.Targets;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;

/**
 * User story 7: sweep parameters (number of devices and fault rate) in batch
 * runs. Every combination is run for each candidate and replication; the
 * comparison has one block per combination.
 */
public final class Story7ParameterSweep {

	public static void main(String[] args) {
		var phone = DevicePresets.SMARTPHONE.withMobility(MobilitySpec.STATIC);
		Experiment.on(Story5CompareFaultModels.city())
				.candidate(Candidate.of("nearest", NearestByDesign::new))
				.candidate(Candidate.of("heartbeat-fallback", HeartbeatFallback::new))
				// extra devices around rsu-2
				.vary("extra_devices", List.of(0, 16), (b, n) -> {
					for (int i = 0; i < n; i++)
						b.addEndDevice("extra-" + i, phone, Layout.near("rsu-2", 150))
								.connect("extra-" + i, "rsu-2", ChannelPresets.CELLULAR_5G);
					return b;
				})
				// mean time between failures of every edge link, seconds (fault rate = 1 / mtbf)
				.vary("link_mtbf_s", List.of(600.0, 120.0), (b, mtbf) -> b.withFaults(StochasticFaultModel.create()
						.linkFailures(Targets.links().touching(Targets.nodes(Tier.EDGE_STATIC)),
								StochasticFaultModel.exp(mtbf), StochasticFaultModel.exp(20))
						.named("random-links")))
				.replications(Examples.replications(3))
				.metrics(Metrics.TASK_SUCCESS, Metrics.LATENCY_P95, Metrics.CONTROL_OVERHEAD, Metrics.TIME_TO_RECOVER)
				.charts(false).run(Examples.output("story7"));
	}
}
