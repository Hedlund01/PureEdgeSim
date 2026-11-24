package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.NearestByDesign;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultRecord;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultType;
import zip.hedlund.pureedgesim.edgesdk.metrics.TimelineSample;

/** Crash records in the fault log span exactly the time the node was down. */
@Tag("integration")
class CrashLogIT {

	private static RunResult run(String script, Path out) {
		return TestScenarios.twoRegions(1).withFaults(FaultScript.parse(script)).build()
				.run(Candidate.of("nearest", NearestByDesign::new), out);
	}

	private static int nodesDownAt(RunResult r, double time) {
		return r.data().timeline().stream().filter(s -> Math.abs(s.time() - time) < 1e-6)
				.mapToInt(TimelineSample::nodesDown).findFirst().orElseThrow();
	}

	@Test
	void aCrashOfACrashedNodeExtendsTheOneRecord(@TempDir Path out) {
		// down 12..52: the second crash outlives the first
		RunResult r = run("12s crash edge-a recover 30s\n22s crash edge-a recover 30s", out);
		List<FaultRecord> crashes = r.data().faults().of(FaultType.CRASH_RECOVERY);
		assertEquals(1, crashes.size());
		assertEquals(12, crashes.get(0).start(), 1e-9);
		assertEquals(52, crashes.get(0).end(), 1e-9);
		assertEquals(1, nodesDownAt(r, 50));
		assertEquals(0, nodesDownAt(r, 60));
	}

	@Test
	void aShorterCrashInsideAnOutageAddsNoRecord(@TempDir Path out) {
		RunResult r = run("10s crash edge-a recover 30s\n20s crash edge-a recover 5s", out);
		List<FaultRecord> crashes = r.data().faults().of(FaultType.CRASH_RECOVERY);
		assertEquals(1, crashes.size());
		assertEquals(10, crashes.get(0).start(), 1e-9);
		assertEquals(40, crashes.get(0).end(), 1e-9);
	}

	@Test
	void aCrashStopDuringAnOutageEndsTheRecoverableRecord(@TempDir Path out) {
		RunResult r = run("10s crash edge-a recover 30s\n20s crash edge-a", out);
		FaultRecord rec = r.data().faults().of(FaultType.CRASH_RECOVERY).get(0);
		assertEquals(10, rec.start(), 1e-9);
		assertEquals(20, rec.end(), 1e-9);
		FaultRecord stop = r.data().faults().of(FaultType.CRASH_STOP).get(0);
		assertEquals(20, stop.start(), 1e-9);
		assertEquals(Double.POSITIVE_INFINITY, stop.end());
		assertEquals(1, nodesDownAt(r, 100));
	}
}
