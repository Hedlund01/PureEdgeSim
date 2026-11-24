package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.NearestByDesign;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;

@Tag("integration")
class SmokeIT {

	@Test
	void faultFreeRunSucceeds(@TempDir Path out) {
		RunResult r = TestScenarios.twoRegions(3).build().run(Candidate.of("nearest", NearestByDesign::new), out);
		System.out.println(r.metrics());
		assertTrue(r.data().tasks().size() > 50);
		assertTrue(r.value(Metrics.TASK_SUCCESS) > 95, "success " + r.value(Metrics.TASK_SUCCESS));
	}
}
