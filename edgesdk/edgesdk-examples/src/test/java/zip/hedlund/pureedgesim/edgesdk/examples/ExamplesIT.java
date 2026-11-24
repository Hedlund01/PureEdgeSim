package zip.hedlund.pureedgesim.edgesdk.examples;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every example runs (shortened) and writes its results. */
@Tag("integration")
class ExamplesIT {

	@TempDir
	static Path out;

	@BeforeAll
	static void quick() {
		System.setProperty("edgesdk.output", out.toString());
		System.setProperty("edgesdk.quick", "true");
	}

	@AfterAll
	static void reset() {
		System.clearProperty("edgesdk.output");
		System.clearProperty("edgesdk.quick");
	}

	private static void ran(String story) {
		assertTrue(Files.exists(out.resolve(story).resolve("runs.csv")), story);
	}

	@Test
	void story1() {
		Story1CustomOffloading.main(new String[0]);
		ran("story1");
	}

	@Test
	void story2() {
		Story2ReplicationUnderPartition.main(new String[0]);
		ran("story2");
	}

	@Test
	void story3() {
		Story3UavSwarmFromPresets.main(new String[0]);
		ran("story3");
	}

	@Test
	void story4() {
		Story4CustomPresets.main(new String[0]);
		ran("story4");
	}

	@Test
	void story5() {
		Story5CompareFaultModels.main(new String[0]);
		ran("story5");
	}

	@Test
	void story6() {
		Story6ScriptedPartitionAndHeal.main(new String[0]);
		ran("story6");
	}

	@Test
	void story7() {
		Story7ParameterSweep.main(new String[0]);
		ran("story7");
	}

	@Test
	void story8() {
		Story8CompareAlgorithms.main(new String[0]);
		ran("story8");
	}

	@Test
	void story9() {
		Story9MvtoraPostDisasterRescue.main(new String[0]);
		ran("story9");
	}
}
