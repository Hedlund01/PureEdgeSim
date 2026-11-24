package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import zip.hedlund.pureedgesim.edgesdk.metrics.TaskOutcome;
import zip.hedlund.pureedgesim.edgesdk.metrics.TaskRecord;
import zip.hedlund.pureedgesim.edgesdk.model.ComputeSpec;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.model.WorkloadSpec;
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
		return twoEdges(60, WorkloadPresets.VIDEO_ANALYTICS.withLengthMi(20_000).withDeadlineS(5));
	}

	private static Scenario twoEdges(double duration, WorkloadSpec workload) {
		var phone = DevicePresets.SMARTPHONE.withMobility(MobilitySpec.STATIC);
		return Scenario.builder("halves").duration(duration).seed(1).map(1_000, 1_000)
				.addEdgeNode("e1", EdgePresets.ROADSIDE_SERVER, Layout.at(500, 500))
				.addEdgeNode("e2", EdgePresets.ROADSIDE_SERVER, Layout.at(520, 500))
				.addEndDevice("p", phone, Layout.at(510, 520))
				.connect(Select.name("p"), Select.name("e*"), ChannelPresets.WIFI).withWorkload(workload).build();
	}

	@Test
	void aSplitTaskRunsItsPartsInParallel(@TempDir Path out) {
		Halves whole = new Halves(false);
		Halves halves = new Halves(true);
		RunResult w = twoEdges().run(Candidate.of("whole", () -> whole), out.resolve("whole"));
		RunResult h = twoEdges().run(Candidate.of("split", () -> halves), out.resolve("split"));
		assertTrue(w.data().tasks().stream().allMatch(t -> t.outcome().succeeded()));
		assertTrue(h.data().tasks().stream().allMatch(t -> t.outcome().succeeded()));
		// executedOn stays one node (the largest share, the first on a tie); parts lists them all
		assertTrue(h.data().tasks().stream()
				.allMatch(t -> t.executedOn().equals("e1") && t.parts().equals(List.of("e1", "e2")) && t.split()));
		assertTrue(w.data().tasks().stream().allMatch(t -> t.parts().equals(List.of("e1")) && !t.split()));
		assertEquals(List.of(NodeRef.of("e1"), NodeRef.of("e2")), halves.lastParts);
		// 20 000 MI on one 25 000 MIPS core: 0.8 s whole, 0.4 s in two halves
		double wholeMean = whole.latency / whole.done;
		double splitMean = halves.latency / halves.done;
		assertTrue(splitMean < wholeMean - 0.3, "whole " + wholeMean + " s, split " + splitMean + " s");
	}

	/** Splits every task over fixed shares; on a timeout, sends it whole to {@code fallback}. */
	static final class Split extends OrchestrationPolicy {
		final Map<String, Double> shares;
		final double retryAfter;
		final List<NodeRef> tried = new ArrayList<>();

		Split(Map<String, Double> shares, double retryAfter) {
			this.shares = shares;
			this.retryAfter = retryAfter;
		}

		@Override
		public Placement place(NodeRef device, TaskRequest task) {
			Map<NodeRef, Double> m = new LinkedHashMap<>();
			shares.forEach((n, v) -> m.put(NodeRef.of(n.replace("$device", device.name())), v));
			return Placement.split(m).withRetryAfter(retryAfter);
		}

		@Override
		public Placement onAttemptTimedOut(NodeRef device, TaskRequest task, NodeRef tried, int attempt) {
			this.tried.add(tried);
			return Placement.on(NodeRef.of("e1"));
		}
	}

	private static Map<String, Double> ordered(String a, double x, String b, double y) {
		Map<String, Double> m = new LinkedHashMap<>();
		m.put(a, x);
		m.put(b, y);
		return m;
	}

	@Test
	void aSplitCanGiveAPartToTheDeviceItself(@TempDir Path out) {
		RunResult r = twoEdges().run(Candidate.of("self", () -> new Split(ordered("e1", 0.6, "$device", 0.4), 0)),
				out);
		assertTrue(r.data().tasks().stream()
				.allMatch(t -> t.outcome().succeeded() && t.parts().equals(List.of("e1", "p"))), r.data().tasks()
						.toString());
	}

	@Test
	void aLostPartLosesTheAttemptUnlessThePolicyRetries(@TempDir Path out) {
		FaultModel crash = FaultScript.parse("20s crash e2\n");
		Scenario s = twoEdges().toBuilder().withFaults(crash).build();
		RunResult lost = s.run(Candidate.of("no-retry", () -> new Split(ordered("e1", 0.5, "e2", 0.5), 0)),
				out.resolve("lost"));
		List<TaskRecord> after = lost.data().tasks().stream().filter(t -> t.createdAt() > 20).toList();
		assertTrue(!after.isEmpty());
		// e1's half comes back, e2's never does: the task fails with the cause of the lost part
		assertTrue(after.stream().allMatch(t -> t.outcome() == TaskOutcome.NODE_CRASHED), after.toString());

		Split retrying = new Split(ordered("e1", 0.5, "e2", 0.5), 1.5);
		RunResult saved = s.run(Candidate.of("retry", () -> retrying), out.resolve("saved"));
		List<TaskRecord> later = saved.data().tasks().stream().filter(t -> t.createdAt() > 20).toList();
		// the retry runs the task whole on e1 while the first attempt's e1 half may still be running
		assertTrue(later.stream().allMatch(t -> t.outcome().succeeded() && t.attempts() == 2
				&& t.parts().equals(List.of("e1"))), later.toString());
		// the hook names the part that did not come back
		assertTrue(!retrying.tried.isEmpty() && retrying.tried.stream().allMatch(n -> n.name().equals("e2")));
	}

	@Test
	void aPartLostWhileItRunsLosesTheTask(@TempDir Path out) {
		// each half takes 4 s on its node; e2 crashes at 60 s with halves running on it
		Scenario s = twoEdges(120,
				WorkloadPresets.VIDEO_ANALYTICS.withLengthMi(200_000).withDeadlineS(10).withRatePerMinute(60))
				.withFaults(FaultScript.parse("60s crash e2\n"));
		RunResult r = s.run(Candidate.of("halves", () -> new Split(ordered("e1", 0.5, "e2", 0.5), 0)), out);
		// created more than 0.2 s before the crash: the input reached e2, which then crashed
		List<TaskRecord> running = r.data().tasks().stream()
				.filter(t -> t.createdAt() > 56.5 && t.createdAt() < 59.8).toList();
		assertTrue(!running.isEmpty());
		assertTrue(running.stream().allMatch(t -> t.outcome() == TaskOutcome.NODE_CRASHED), running.toString());
		// earlier tasks finished on both nodes
		assertTrue(r.data().tasks().stream().filter(t -> t.createdAt() < 50)
				.allMatch(t -> t.outcome().succeeded() && t.split()));
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
		assertTrue(tasks.stream().anyMatch(TaskRecord::split));
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

	/**
	 * Six UAVs out of the cars' reach send more work than a small E-UAV can serve.
	 * EMC must offload everything and builds a queue; MTO sees the queue through
	 * the E-UAV's reservations and keeps the excess local.
	 */
	@ParameterizedTest
	@ValueSource(longs = { 6, 7, 8 })
	void theEuavBacklogStopsOffloadingWhenItIsFull(long seed, @TempDir Path out) {
		var uav = DevicePresets.UAV.withMobility(MobilitySpec.STATIC);
		var euav = EdgePresets.ROADSIDE_SERVER.withCompute(c -> ComputeSpec.of(2, 7_500));
		var b = Scenario.builder("overload").duration(120).seed(seed).map(2_000, 2_000)
				.addEdgeNode("euav", euav, Layout.at(1_000, 1_000));
		for (int i = 0; i < 6; i++)
			b.addEndDevice("uav-" + i, uav, Layout.at(900 + 30 * i, 950));
		Scenario s = b.connect(Select.tier(Tier.END_DEVICE), Select.name("euav"), ChannelPresets.CELLULAR_5G)
				.withWorkload(WorkloadPresets.VIDEO_ANALYTICS.withRatePerMinute(60)).withFaults(NoFaults.INSTANCE)
				.build();
		RunResult emc = s.run(mode(EnumSet.of(Mvtora.Mode.MEC)), out.resolve("emc"));
		RunResult mto = s.run(mode(EnumSet.of(Mvtora.Mode.LOCAL, Mvtora.Mode.MEC)), out.resolve("mto"));
		double emcOk = successPct(emc);
		double mtoOk = successPct(mto);
		assertTrue(emcOk < 80, "emc " + emcOk);
		// 85 % on seed 6 before the devices counted the arrivals they cannot see yet; 96–98 % over seeds 6–10
		assertTrue(mtoOk > 93, "mto " + mtoOk);
		long toEuav = mto.data().tasks().stream().filter(t -> t.executedOn().equals("euav")).count();
		assertTrue(toEuav > 0 && toEuav < mto.data().tasks().size() * 0.8, "to euav " + toEuav);
	}

	private static Candidate mode(Set<Mvtora.Mode> modes) {
		return Candidate.of("m",
				() -> new Mvtora(Mvtora.Params.defaults(), Select.name("euav"), Select.name("none"), modes));
	}

	private static double successPct(RunResult r) {
		List<TaskRecord> t = r.data().tasks();
		return 100.0 * t.stream().filter(x -> x.outcome().succeeded()).count() / t.size();
	}
}
