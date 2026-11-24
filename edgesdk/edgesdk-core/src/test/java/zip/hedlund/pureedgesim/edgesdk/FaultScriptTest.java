package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScheduler;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.fault.LinkSelect;
import zip.hedlund.pureedgesim.edgesdk.fault.Recovery;
import zip.hedlund.pureedgesim.edgesdk.fault.ScriptedFaultModel;
import zip.hedlund.pureedgesim.edgesdk.fault.StochasticFaultModel;
import zip.hedlund.pureedgesim.edgesdk.fault.Targets;
import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.network.Degradation;

class FaultScriptTest {

	/** Records what a model schedules, resolving targets immediately. */
	static final class Recorder implements FaultScheduler {
		final DesignTopology t;
		final List<String> events = new ArrayList<>();

		Recorder(DesignTopology t) {
			this.t = t;
		}

		@Override
		public void linkDown(LinkSelect target, double at, double duration) {
			events.add(String.format(java.util.Locale.ROOT, "linkdown %s %.3f %.3f", target.resolve(t), at, duration));
		}

		@Override
		public void degrade(LinkSelect target, double at, double duration, Degradation d) {
			events.add("degrade " + target.resolve(t) + " " + at + " " + duration + " " + d);
		}

		@Override
		public void crash(Select target, double at, Recovery recovery) {
			events.add(String.format(java.util.Locale.ROOT, "crash %s %.3f %s", target.resolve(t), at,
					recovery.recovers() ? recovery.after() : "never"));
		}

		@Override
		public void partition(List<Select> groups, double at, double duration) {
			events.add("partition " + groups.stream().map(g -> g.resolve(t).toString()).toList() + " " + at + " "
					+ duration);
		}

		@Override
		public void at(double time, Runnable action) {
		}

		@Override
		public double now() {
			return 0;
		}

		@Override
		public double duration() {
			return 3_600;
		}
	}

	private final DesignTopology topology = TestScenarios.twoRegions(2).build().design();

	@Test
	void parsesEveryEvent() {
		ScriptedFaultModel m = FaultScript.parse("""
				# a comment
				120s partition a | edge-b,cloud for 60s
				2m crash pa-1
				400s crash edge-a recover 30s   # trailing comment
				200s linkdown edge-a cloud for 90s
				500s degrade edge-a cloud latency+200ms loss=5% bw*0.5 for 1m
				""");
		Recorder r = new Recorder(topology);
		m.install(r, topology, new Random(1));
		assertEquals(5, r.events.size());
		assertEquals("partition [[edge-a, pa-0, pa-1], [edge-b, cloud]] 120.0 60.0", r.events.get(0));
		assertEquals("crash [pa-1] 120.000 never", r.events.get(1));
		assertEquals("crash [edge-a] 400.000 30.0", r.events.get(2));
		assertEquals("linkdown [edge-a<->cloud(fiber)] 200.000 90.000", r.events.get(3));
		assertTrue(r.events.get(4).contains("Degradation[extraLatencyS=0.2, extraLoss=0.05"), r.events.get(4));
	}

	@Test
	void timesAcceptUnits() {
		assertEquals(0.5, FaultScript.parseTime("500ms"));
		assertEquals(120, FaultScript.parseTime("2m"));
		assertEquals(7200, FaultScript.parseTime("2h"));
		assertEquals(42, FaultScript.parseTime("42"));
	}

	@Test
	void badLinesAreReportedWithTheirNumber() {
		var e = assertThrows(IllegalArgumentException.class, () -> FaultScript.parse("10s crash x\n20s explode y"));
		assertTrue(e.getMessage().contains("line 2"), e.getMessage());
		assertThrows(IllegalArgumentException.class, () -> FaultScript.parse("10s partition a | b"));
	}

	@Test
	void scriptRoundTripsThroughText() {
		ScriptedFaultModel m = FaultScript.builder().partition(10, 5, "a", "b").crash("pa-0", 20, Recovery.after(3))
				.linkDown("edge-a", "cloud", 30, 4).build();
		Recorder r1 = new Recorder(topology);
		Recorder r2 = new Recorder(topology);
		m.install(r1, topology, new Random(1));
		FaultScript.parse(m.toScript()).install(r2, topology, new Random(1));
		assertEquals(r1.events, r2.events);
	}

	@Test
	void stochasticModelIsDeterministicPerSeed() {
		StochasticFaultModel m = StochasticFaultModel.create()
				.linkFailures(Targets.links(), StochasticFaultModel.exp(300), StochasticFaultModel.exp(20))
				.nodeCrashes(Targets.nodes(Tier.EDGE_STATIC), StochasticFaultModel.exp(900),
						StochasticFaultModel.exp(30))
				.perLinkProbability(Targets.links().channel(zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.WIFI),
						0.01, 10, StochasticFaultModel.exp(5));
		Recorder a = new Recorder(topology);
		Recorder b = new Recorder(topology);
		Recorder c = new Recorder(topology);
		m.install(a, topology, new Random(42));
		m.install(b, topology, new Random(42));
		m.install(c, topology, new Random(43));
		assertTrue(a.events.size() > 10);
		assertEquals(a.events, b.events);
		assertTrue(!a.events.equals(c.events));
	}

	@Test
	void linkQueries() {
		assertEquals(4, Targets.links().channel(zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.WIFI)
				.resolve(topology).size());
		assertEquals(1, Targets.links().between(Select.group("a"), Select.group("b")).resolve(topology).size());
		assertEquals(2, Targets.links().touching(Select.name("cloud")).resolve(topology).size());
		assertEquals(1, Targets.link("edge-b", "edge-a").resolve(topology).size());
	}

	@Test
	void compositeLabels() {
		var m = FaultScript.builder().build().and(StochasticFaultModel.links(100, 10));
		assertEquals("scripted+stochastic", m.label());
		assertEquals("x", m.labelled("x").label());
		Scenario.builder("y"); // keep import used
	}
}
