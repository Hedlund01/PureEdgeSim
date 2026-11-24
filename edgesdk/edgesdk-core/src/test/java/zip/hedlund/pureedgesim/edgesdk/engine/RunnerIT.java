package zip.hedlund.pureedgesim.edgesdk.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;

import zip.hedlund.pureedgesim.edgesdk.TestScenarios;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.NearestByDesign;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

@Tag("integration")
class RunnerIT {

	private static RunSpec spec(Candidate c, Path coreOutput) {
		return new RunSpec("r", TestScenarios.twoRegions(1).duration(20).build(), c, "none", Map.of(), 0, 1, 2, 3,
				coreOutput);
	}

	/** Writes to the error stream, as PureEdgeSim does for its red console lines. */
	static final class Noisy extends NearestByDesign {
		@Override
		public Placement place(NodeRef device, TaskRequest task) {
			System.err.println("noise");
			return super.place(device, task);
		}
	}

	@Test
	void quietSilencesTheErrorStream(@TempDir Path out) {
		PrintStream err = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		System.setErr(new PrintStream(captured));
		try {
			Runner.run(spec(Candidate.of("noisy", Noisy::new), out), true);
		} finally {
			System.setErr(err);
		}
		assertEquals("", captured.toString());
	}

	@Test
	void aFailedSetupLeavesTheSettingsAsTheyWere(@TempDir Path out) throws IOException {
		double duration = SimulationParameters.simulationDuration;
		String folder = SimulationParameters.outputFolder;
		try {
			SimulationParameters.simulationDuration = 1234;
			SimulationParameters.outputFolder = "before/";
			// the provenance files cannot be written under a regular file
			Path file = Files.createFile(out.resolve("not-a-folder"));
			assertThrows(UncheckedIOException.class,
					() -> Runner.run(spec(Candidate.of("nearest", NearestByDesign::new), file), true));
			assertEquals(1234, SimulationParameters.simulationDuration);
			assertEquals("before/", SimulationParameters.outputFolder);
			// the next run is not blocked
			Runner.run(spec(Candidate.of("nearest", NearestByDesign::new), out), true);
			assertEquals(1234, SimulationParameters.simulationDuration);
		} finally {
			SimulationParameters.simulationDuration = duration;
			SimulationParameters.outputFolder = folder;
		}
	}
}
