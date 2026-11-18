package partitiontestbed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

import partitiontestbed.baselines.AccessServerStrategy;

/**
 * Tests the two ways a strategy can run a task besides sending it whole to a
 * server: on the processor of its own device, and divided into parts that run on
 * several hosts at once.
 */
class PartialOffloadingTest extends TestbedTestSupport {

	/** Runs every task on its device when the device has a processor. */
	static final class LocalStrategy extends AccessServerStrategy {
		static final String NAME = "TEST_LOCAL";

		@Override
		public void onTaskCreated(ComputingNode device, Job job) {
			if (device.getNumberOfCPUCores() > 0 && ctx.startInstance(device, job.service(), null))
				ctx.dispatch(job, device, device);
			else
				super.onTaskCreated(device, job);
		}

		@Override
		public void onTaskArrived(ComputingNode node, Job job) {
			// A stage released on the device after a local stage stays there.
			if (ctx.serverOf(node) == null && node != ctx.cloud())
				onTaskCreated(node, job);
			else
				runHere(node, job);
		}
	}

	/** Divides every task it can into three parts of 1, 1 and 2, all on the access server. */
	static final class SplitStrategy extends AccessServerStrategy {
		static final String NAME = "TEST_SPLIT";
		static int refusedTwice;

		@Override
		public void initialize() {
			refusedTwice = 0;
		}

		@Override
		public void onTaskArrived(ComputingNode node, Job job) {
			if (job.handsOverOnEdge() || !(ctx.hasInstance(node, job.service())
					|| ctx.startInstance(node, job.service(), null))) {
				runHere(node, job);
				return;
			}
			ctx.divide(job, 1, 1, 2);
			try {
				ctx.divide(job, 1);
			} catch (IllegalStateException e) {
				refusedTwice++;
			}
			for (int part = 0; part < job.parts(); part++)
				ctx.dispatchPart(job, part, node, node);
		}
	}

	@Test
	@Tag("integration")
	void aDeviceWithAProcessorRunsItsOwnTasks() throws Exception {
		Testbed.register(LocalStrategy.NAME, LocalStrategy::new);
		testbed("partition.1.enabled", "false");
		String console = run(LocalStrategy.NAME);

		List<Map<String, String>> types = rows(LocalStrategy.NAME, "device_types");
		long devicesWithTasksOnDevice = types.stream().filter(row -> number(row, "done_on_device") > 0).count();
		assertTrue(devicesWithTasksOnDevice > 0, "the tasks of devices with a processor are done on them");
		for (Map<String, String> row : types)
			if ("IoT sensor".equals(row.get("device_type")))
				assertEquals(0, number(row, "done_on_device"), "a sensor has no processor, so its tasks go to the edge");
		List<Map<String, String>> tasks = rows(LocalStrategy.NAME, "tasks");
		assertTrue(tasks.stream().anyMatch(row -> row.get("host").equals(row.get("device"))),
				"and the host of such a task is its device");
		assertTrue(successRate(console) > 0, "they are judged as any other");
	}

	@Test
	@Tag("integration")
	void aDividedTaskIsDoneWhenEveryPartIsAndItsPartsAreNoRedundantRuns() throws Exception {
		Testbed.register(SplitStrategy.NAME, SplitStrategy::new);
		testbed("partition.1.enabled", "false");
		String console = run(SplitStrategy.NAME);

		assertTrue(SplitStrategy.refusedTwice > 0, "a task is divided once");
		List<Map<String, String>> tasks = rows(SplitStrategy.NAME, "tasks");
		long divided = tasks.stream().filter(row -> "3".equals(row.get("attempts"))).count();
		assertTrue(divided > tasks.size() / 2, "most tasks run in three parts: " + divided + " of " + tasks.size());
		assertTrue(tasks.stream().filter(row -> "3".equals(row.get("attempts")))
				.allMatch(row -> "0".equals(row.get("duplicates"))), "a part that comes back is no duplicate");
		assertEquals(0, percent(console, "attempts per task,"), 0.0, "parts are not copies of each other");
		assertEquals(0, percent(console, "lost"), 0.05, "and nothing is lost on a whole network");
	}
}
