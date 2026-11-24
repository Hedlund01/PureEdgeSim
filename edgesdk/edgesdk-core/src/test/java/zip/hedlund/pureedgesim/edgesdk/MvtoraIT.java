package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskReport;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.Mvtora;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultModel;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.fault.NoFaults;
import zip.hedlund.pureedgesim.edgesdk.metrics.TaskRecord;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.EdgePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.WorkloadPresets;

/** Split placements, and MVTORA over an E-UAV, vehicle fog nodes and client UAVs. */
@Tag("integration")
class MvtoraIT {

	/** Splits every task evenly over two edge nodes, or sends it whole to the first. */
	static final class Halves extends OrchestrationPolicy {
		final boolean split;
		double latency;
		int done;
		List<NodeRef> lastParts;

		Halves(boolean split) {
			this.split = split;
		}

		@Override
		public Placement place(NodeRef device, TaskRequest task) {
			if (!split)
				return Placement.on(NodeRef.of("e1"));
			Map<NodeRef, Double> halves = new LinkedHashMap<>();
			halves.put(NodeRef.of("e1"), 0.5);
			halves.put(NodeRef.of("e2"), 0.5);
			return Placement.split(halves);
		}

		@Override
		public void onTaskCompleted(NodeRef device, TaskReport report) {
			latency += report.latency();
			done++;
			lastParts = report.parts();
		}
	}

	private static Scenario twoEdges() {
		var phone = DevicePresets.SMARTPHONE.withMobility(MobilitySpec.STATIC);
		return Scenario.builder("halves").duration(60).seed(1).map(1_000, 1_000)
				.addEdgeNode("e1", EdgePresets.ROADSIDE_SERVER, Layout.at(500, 500))
				.addEdgeNode("e2", EdgePresets.ROADSIDE_SERVER, Layout.at(520, 500))
				.addEndDevice("p", phone, Layout.at(510, 520))
				.connect(Select.name("p"), Select.name("e*"), ChannelPresets.WIFI)
				.withWorkload(WorkloadPresets.VIDEO_ANALYTICS.withLengthMi(20_000).withDeadlineS(5)).build();
	}

	@Test
	void aSplitTaskRunsItsPartsInParallel(@TempDir Path out) {
		Halves whole = new Halves(false);
		Halves halves = new Halves(true);
		RunResult w = twoEdges().run(Candidate.of("whole", () -> whole), out.resolve("whole"));
		RunResult h = twoEdges().run(Candidate.of("split", () -> halves), out.resolve("split"));
		assertTrue(w.data().tasks().stream().allMatch(t -> t.outcome().succeeded()));
		assertTrue(h.data().tasks().stream().allMatch(t -> t.outcome().succeeded()));
		assertTrue(h.data().tasks().stream().allMatch(t -> t.executedOn().equals("e1+e2")));
		assertEquals(List.of(NodeRef.of("e1"), NodeRef.of("e2")), halves.lastParts);
		// 20 000 MI on one 25 000 MIPS core: 0.8 s whole, 0.4 s in two halves
		double wholeMean = whole.latency / whole.done;
		double splitMean = halves.latency / halves.done;
		assertTrue(splitMean < wholeMean - 0.3, "whole " + wholeMean + " s, split " + splitMean + " s");
	}

	/**
	 * Three C-UAVs fly among four cars, three far from them; all reach the E-UAV
	 * over 5G. The near ones can divide tasks among the cars.
	 */
	private static Scenario rescue(FaultModel faults) {
		var uav = DevicePresets.UAV.withMobility(MobilitySpec.STATIC);
		var car = EdgePresets.CAR_COMPUTE.withMobility(MobilitySpec.STATIC);
		var b = Scenario.builder("rescue").duration(120).seed(4).map(2_000, 2_000)
				.addEdgeNode("euav", EdgePresets.ROADSIDE_SERVER, Layout.at(1_000, 1_000));
		for (int i = 0; i < 4; i++)
			b.addEdgeNode("car-" + i, car, Layout.at(1_100 + 20 * i, 1_000));
		for (int i = 0; i < 3; i++)
			b.addEndDevice("near-" + i, uav, Layout.at(1_120 + 10 * i, 1_030));
		for (int i = 0; i < 3; i++)
			b.addEndDevice("far-" + i, uav, Layout.at(700, 800 + 50 * i));
		return b.connect(Select.tier(Tier.END_DEVICE), Select.name("euav"), ChannelPresets.CELLULAR_5G)
				.connect(Select.name("car-*"), Select.name("euav"), ChannelPresets.CELLULAR_5G)
				.connect(Select.name("near-*"), Select.name("car-*"), ChannelPresets.WIFI)
				.withWorkload(WorkloadPresets.VIDEO_ANALYTICS.withRatePerMinute(30)).withFaults(faults).build();
	}

	@Test
	void mvtoraComputesLocallyOffloadsToTheEuavAndDividesTasksAmongVehicles(@TempDir Path out) {
		RunResult r = rescue(NoFaults.INSTANCE).run(Candidate.of("mvtora", Mvtora::new), out);
		Map<String, Double> c = r.data().custom();
		List<TaskRecord> tasks = r.data().tasks();
		long ok = tasks.stream().filter(t -> t.outcome().succeeded()).count();
		assertTrue(ok >= 0.95 * tasks.size(), ok + " of " + tasks.size() + " " + c);
		assertTrue(c.getOrDefault("mvtora.mec", 0.0) > 0, c.toString());
		assertTrue(c.getOrDefault("mvtora.vfc", 0.0) > 0, c.toString());
		assertTrue(c.get("mvtora.utility.mean") > 0, c.toString());
		// only the UAVs near the cars use them, and they divide the task
		assertTrue(tasks.stream().filter(t -> t.executedOn().startsWith("car-"))
				.allMatch(t -> t.device().startsWith("near-")));
		assertTrue(tasks.stream().anyMatch(t -> t.executedOn().contains("+")));
		// before the first slot report arrives, a UAV computes locally
		assertTrue(tasks.stream().filter(t -> t.createdAt() < 0.5).allMatch(t -> t.executedOn().equals(t.device())));
	}

	@Test
	void devicesThatStopHearingFromTheEuavStopUsingIt(@TempDir Path out) {
		RunResult r = rescue(FaultScript.parse("40s crash euav recover 40s\n"))
				.run(Candidate.of("mvtora", Mvtora::new), out);
		List<TaskRecord> tasks = r.data().tasks();
		// three slots after the crash, no task goes to the E-UAV, and tasks succeed again
		List<TaskRecord> later = tasks.stream().filter(t -> t.createdAt() > 44 && t.createdAt() < 78).toList();
		assertTrue(!later.isEmpty());
		assertTrue(later.stream().noneMatch(t -> t.executedOn().equals("euav")));
		assertTrue(later.stream().allMatch(t -> t.outcome().succeeded()), later.toString());
		// after the recovery the E-UAV is used again
		assertTrue(tasks.stream().anyMatch(t -> t.createdAt() > 85 && t.executedOn().equals("euav")));
	}
}
