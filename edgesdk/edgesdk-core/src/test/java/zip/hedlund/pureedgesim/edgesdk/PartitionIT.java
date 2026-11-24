package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.CloudOnly;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.HeartbeatFallback;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.NearestByDesign;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultRecord;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultType;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.metrics.TaskOutcome;
import zip.hedlund.pureedgesim.edgesdk.metrics.TaskRecord;
import zip.hedlund.pureedgesim.edgesdk.metrics.TimelineSample;

@Tag("integration")
class PartitionIT {

	private static TimelineSample at(RunResult r, double time) {
		return r.data().timeline().stream().filter(s -> Math.abs(s.time() - time) < 1e-6).findFirst().orElseThrow();
	}

	@Test
	void partitionCutsExactlyTheLinksBetweenGroupsAndHealRestoresThem(@TempDir Path out) {
		// region a against everything else, 40..70 s
		Scenario s = TestScenarios.twoRegions(2).withFaults(FaultScript.parse("40s partition a for 30s")).build();
		RunResult r = s.run(Candidate.of("nearest", NearestByDesign::new), out);
		assertEquals(0, at(r, 30).linksDown());
		assertEquals(2, at(r, 50).linksDown()); // edge-a<->cloud and edge-a<->edge-b
		assertTrue(at(r, 50).partitionActive());
		assertEquals(0, at(r, 80).linksDown());
		FaultRecord p = r.data().faults().of(FaultType.PARTITION).get(0);
		assertEquals(40, p.start(), 1e-9);
		assertEquals(70, p.end(), 1e-9);
		assertEquals("edge-a,pa-0,pa-1|edge-b,cloud,pb-0,pb-1", p.groups());
	}

	@Test
	void overlappingLinkFailureOutlivesThePartition(@TempDir Path out) {
		Scenario s = TestScenarios.twoRegions(1)
				.withFaults(FaultScript.parse("40s partition a for 30s\n30s linkdown edge-a cloud for 60s")).build();
		RunResult r = s.run(Candidate.of("nearest", NearestByDesign::new), out);
		assertEquals(2, at(r, 60).linksDown());
		assertEquals(1, at(r, 80).linksDown()); // the link failure still holds edge-a<->cloud
		assertEquals(0, at(r, 100).linksDown());
	}

	@Test
	void cloudBoundTasksFailDuringThePartitionAndRecoverAfterHeal(@TempDir Path out) {
		Scenario s = TestScenarios.twoRegions(3).withFaults(FaultScript.parse("40s partition a for 30s")).build();
		RunResult r = s.run(Candidate.of("cloud", CloudOnly::new), out);
		List<TaskRecord> regionA = r.data().tasks().stream().filter(t -> t.device().startsWith("pa-")).toList();
		long failedDuring = regionA.stream().filter(TaskRecord::duringPartition)
				.filter(t -> !t.outcome().succeeded()).count();
		long totalDuring = regionA.stream().filter(TaskRecord::duringPartition).count();
		assertTrue(totalDuring > 10);
		assertTrue(failedDuring >= totalDuring - 1, failedDuring + "/" + totalDuring);
		assertTrue(regionA.stream().filter(t -> t.createdAt() > 71).allMatch(t -> t.outcome().succeeded()));
		// failures carry their true reason
		assertTrue(regionA.stream().anyMatch(t -> t.outcome() == TaskOutcome.NO_ROUTE));
		// region b is unaffected
		assertTrue(r.data().tasks().stream().filter(t -> t.device().startsWith("pb-"))
				.allMatch(t -> t.outcome().succeeded()));
		double ttr = r.value(Metrics.TIME_TO_RECOVER);
		assertTrue(ttr >= 0 && ttr < 5, "time to recover " + ttr);
	}

	@Test
	void heartbeatFallbackDetectsThePartitionAndRunsLocally(@TempDir Path out) {
		// devices of region a lose their edge server: only a local fallback helps
		Scenario s = TestScenarios.twoRegions(3).withFaults(FaultScript.parse("40s partition pa-* for 30s")).build();
		RunResult nearest = s.run(Candidate.of("nearest", NearestByDesign::new), out.resolve("n"));
		RunResult fallback = s.run(Candidate.of("fallback", HeartbeatFallback::new), out.resolve("f"));
		double n = nearest.value(Metrics.SUCCESS_DURING_PARTITION);
		double f = fallback.value(Metrics.SUCCESS_DURING_PARTITION);
		assertTrue(f > n + 20, "fallback " + f + " vs nearest " + n);
		double detection = fallback.value(Metrics.DETECTION_LATENCY);
		assertTrue(detection > 0 && detection < 3, "detection latency " + detection);
		assertTrue(fallback.value(Metrics.CONTROL_OVERHEAD) > 0);
		assertEquals(0.0, nearest.value(Metrics.CONTROL_OVERHEAD));
		double falsePct = fallback.value(Metrics.FALSE_SUSPICIONS);
		assertTrue(falsePct < 50, "false suspicions " + falsePct);
	}

	@Test
	void crashLosesTheTasksRunningThere(@TempDir Path out) {
		Scenario s = TestScenarios.twoRegions(3).withFaults(FaultScript.parse("40s crash edge-a recover 20s"))
				.build();
		RunResult r = s.run(Candidate.of("nearest", NearestByDesign::new), out);
		assertTrue(r.data().tasks().stream().anyMatch(
				t -> t.outcome() == TaskOutcome.NODE_CRASHED || t.outcome() == TaskOutcome.NO_ROUTE));
		assertEquals(FaultType.CRASH_RECOVERY, r.data().faults().records().get(0).type());
		assertTrue(r.data().tasks().stream().filter(t -> t.device().startsWith("pa-") && t.createdAt() > 61)
				.allMatch(t -> t.outcome().succeeded()));
	}
}
