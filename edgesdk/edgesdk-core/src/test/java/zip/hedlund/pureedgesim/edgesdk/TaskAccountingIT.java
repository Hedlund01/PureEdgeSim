package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.metrics.TaskOutcome;
import zip.hedlund.pureedgesim.edgesdk.metrics.TaskRecord;
import zip.hedlund.pureedgesim.edgesdk.metrics.TimelineSample;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.CloudPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.WorkloadPresets;

/** How tasks are counted: outcomes, timeline windows and failure causes agree. */
@Tag("integration")
class TaskAccountingIT {

	static final class Local extends OrchestrationPolicy {
		@Override
		public Placement place(NodeRef device, TaskRequest task) {
			return Placement.local();
		}
	}

	@Test
	void resultAtTheDeadlineIsLateAndCountedOnceInTheTimeline(@TempDir Path out) {
		// 2 000 MI on 1 000 MIPS: every result arrives exactly at its 2 s deadline
		var phone = DevicePresets.SMARTPHONE.withMobility(MobilitySpec.STATIC)
				.withCompute(c -> c.withMipsPerCore(1_000));
		Scenario s = Scenario.builder("tie").duration(60).seed(3).map(1_000, 1_000)
				.addEndDevice("p", phone, Layout.at(100, 100))
				.addCloud("cloud", CloudPresets.REGIONAL_DC, Layout.at(500, 900))
				.connect("p", "cloud", ChannelPresets.CELLULAR_4G)
				.withWorkload(WorkloadPresets.SENSOR_TELEMETRY.withRatePerMinute(20).withLengthMi(2_000)
						.withDeadlineS(2), Select.tier(Tier.END_DEVICE))
				.build();
		RunResult r = s.run(Candidate.of("local", Local::new), out);
		List<TaskRecord> tasks = r.data().tasks();
		long ok = tasks.stream().filter(t -> t.outcome().succeeded()).count();
		int done = r.data().timeline().stream().mapToInt(TimelineSample::tasksDone).sum();
		int failed = r.data().timeline().stream().mapToInt(TimelineSample::tasksFailed).sum();
		assertEquals(ok, done);
		assertEquals(tasks.size() - ok, failed);
		assertTrue(tasks.stream().anyMatch(t -> t.outcome() == TaskOutcome.LATE));
	}
}
