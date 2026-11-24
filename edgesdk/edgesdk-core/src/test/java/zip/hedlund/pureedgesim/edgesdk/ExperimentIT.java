package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.CloudOnly;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.HeartbeatFallback;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.NearestByDesign;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Experiment;
import zip.hedlund.pureedgesim.edgesdk.api.ExperimentResult;
import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.fault.NoFaults;
import zip.hedlund.pureedgesim.edgesdk.fault.StochasticFaultModel;
import zip.hedlund.pureedgesim.edgesdk.metrics.CsvExporter;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;

@Tag("integration")
class ExperimentIT {

	private static Scenario scenario() {
		return TestScenarios.twoRegions(3).withFaults(FaultScript.parse("40s partition pa-* for 30s")).build();
	}

	private static Experiment threeCandidates(Scenario s) {
		return Experiment.on(s).candidate(Candidate.of("cloud", CloudOnly::new))
				.candidate(Candidate.of("nearest", NearestByDesign::new))
				.candidate(Candidate.of("fallback", HeartbeatFallback::new)).baseline("cloud").replications(2)
				.metrics(Metrics.TASK_SUCCESS, Metrics.SUCCESS_DURING_PARTITION, Metrics.LATENCY_MEAN)
				.charts(false).progress(false);
	}

	@Test
	void candidatesFaceIdenticalConditions(@TempDir Path out) throws IOException {
		ExperimentResult r = threeCandidates(scenario()).run(out);
		assertEquals(6, r.runs().size());
		for (int rep = 0; rep < 2; rep++) {
			int replication = rep;
			List<RunResult> same = r.runs().stream().filter(x -> x.data().replication() == replication).toList();
			var faults = same.get(0).data().faults().records();
			var arrivals = same.get(0).data().tasks().stream().map(t -> t.device() + "@" + t.createdAt()).toList();
			for (RunResult x : same) {
				assertEquals(faults, x.data().faults().records(), "faults differ for " + x.candidate());
				assertEquals(arrivals, x.data().tasks().stream().map(t -> t.device() + "@" + t.createdAt()).toList());
			}
		}
		// replications differ in their workload
		assertTrue(r.runs().get(0).data().tasks().size() != r.runs().get(3).data().tasks().size()
				|| !r.runs().get(0).data().tasks().get(0).equals(r.runs().get(3).data().tasks().get(0)));
		// the comparison: one row per condition, candidate and metric
		assertEquals(1 * 3 * 3, r.report().rows().size());
		assertEquals(1 + 9, Files.readAllLines(out.resolve("comparison.csv")).size());
		// a device-side fallback beats cloud-only during the partition
		assertTrue(r.delta("fallback", "cloud", Metrics.SUCCESS_DURING_PARTITION) > 0);
		assertEquals("fallback", r.best(Metrics.SUCCESS_DURING_PARTITION).orElseThrow());
		var row = r.report().row(r.report().conditions().get(0), "cloud", Metrics.TASK_SUCCESS.name()).orElseThrow();
		assertEquals(0.0, row.delta(), 1e-12);
		for (String f : List.of("runs.csv", "tasks.csv", "faults.csv", "timeline.csv", "storage.csv",
				"comparison.csv", "comparison_wide.csv"))
			assertTrue(Files.exists(out.resolve(f)), f);
		assertTrue(CsvExporter.header(out.resolve("runs.csv")).containsAll(
				List.of("run_id", "candidate", "condition", "replication", "task_success_pct", "time_to_recover_s")));
		assertTrue(CsvExporter.header(out.resolve("comparison.csv"))
				.containsAll(List.of("condition", "candidate", "metric", "mean", "ci95", "delta_vs_baseline", "best")));
	}

	@Test
	void sameSeedsGiveIdenticalResults(@TempDir Path out) throws IOException {
		threeCandidates(scenario()).run(out.resolve("1"));
		threeCandidates(scenario()).run(out.resolve("2"));
		assertEquals(Files.readAllLines(out.resolve("1/runs.csv")), Files.readAllLines(out.resolve("2/runs.csv")));
		assertEquals(Files.readAllLines(out.resolve("1/tasks.csv")), Files.readAllLines(out.resolve("2/tasks.csv")));
	}

	@Test
	void sweepsFormACartesianProduct(@TempDir Path out) throws IOException {
		var phone = DevicePresets.SMARTPHONE.withMobility(MobilitySpec.STATIC);
		ExperimentResult r = Experiment.on(TestScenarios.twoRegions(1).duration(60).build())
				.candidate(Candidate.of("nearest", NearestByDesign::new))
				.candidate(Candidate.of("fallback", HeartbeatFallback::new))
				.faultModels(NoFaults.INSTANCE, StochasticFaultModel.links(60, 5).named("random"))
				.vary("extra", List.of(0, 4), (b, n) -> {
					for (int i = 0; i < n; i++)
						b.addEndDevice("x-" + i, phone, Layout.near("edge-b", 40)).connect("x-" + i, "edge-b",
								ChannelPresets.WIFI);
					return b;
				}).charts(false).progress(false).run(out);
		assertEquals(2 * 2 * 2, r.runs().size());
		assertEquals(4, r.report().conditions().size());
		List<String> lines = Files.readAllLines(out.resolve("runs.csv"));
		assertEquals(1 + 8, lines.size());
		assertTrue(lines.get(0).contains("param.extra"));
		assertEquals(List.of("0", "4"), r.runs().stream().map(x -> x.data().params().get("extra")).distinct()
				.collect(Collectors.toList()));
	}

	@Test
	void sweptFaultModelsLabelTheirConditions(@TempDir Path out) {
		ExperimentResult r = Experiment.on(TestScenarios.twoRegions(1).duration(60).build())
				.candidate(Candidate.of("nearest", NearestByDesign::new))
				.vary("mtbf", List.of(30.0, 60.0),
						(b, m) -> b.withFaults(StochasticFaultModel.links(m, 5).named("random-" + m.intValue())))
				.charts(false).progress(false).run(out);
		assertEquals(List.of("random-30", "random-60"), r.runs().stream().map(RunResult::condition).toList());
	}
}
