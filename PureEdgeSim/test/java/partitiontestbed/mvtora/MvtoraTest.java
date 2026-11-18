package partitiontestbed.mvtora;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import partitiontestbed.TestbedTestSupport;

/**
 * Tests MVTORA: its models and algorithms against what the paper says of them,
 * and the strategy on the testbed.
 */
class MvtoraTest extends TestbedTestSupport {

	private static final String MVTORA = MvtoraStrategy.NAME;

	@AfterEach
	void clearMvtora() {
		for (String key : System.getProperties().stringPropertyNames())
			if (key.startsWith(MvtoraConfig.PREFIX))
				System.clearProperty(key);
	}

	private static Mvtora model() {
		return new Mvtora(new MvtoraConfig("no such file"));
	}

	// --------------------------------------------------------- configuration

	@Test
	void theDefaultsAreThoseOfTableTwo() {
		MvtoraConfig config = new MvtoraConfig("no such file");
		assertEquals(0.9, config.alpha);
		assertEquals(0.1, config.energyWeight);
		assertEquals(0.1, config.transmitPower, 1e-12, "20 dBm");
		assertEquals(5, config.maxFogNodes);
		assertEquals(200, config.generations);
		assertEquals(50, config.population);
		assertEquals(MvtoraConfig.FogNodes.MOBILE, config.fogNodes, "the fog nodes are the vehicles");
	}

	@Test
	void anInvalidValueFailsBeforeTheRunStarts() {
		System.setProperty("mvtora.vfc.fog_nodes", "some");
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new MvtoraConfig("no such file"));
		assertTrue(e.getMessage().contains("vfc.fog_nodes"), e.getMessage());
	}

	// ------------------------------------------------------------- utility

	@Test
	void aWayThatMissesTheDeadlineRanksBelowAnyThatMeetsItAndTheFasterFirst() {
		Mvtora model = model();
		Mvtora.Task task = new Mvtora.Task(1e6, 1, 0.5);
		double onTime = model.utility(task, 0.49, 1000, 30);
		double late = model.utility(task, 0.6, 0, 0);
		double later = model.utility(task, 2, 0, 0);
		assertTrue(Mvtora.feasible(onTime));
		assertFalse(Mvtora.feasible(late));
		assertTrue(onTime > late && late > later);
	}

	@Test
	void localEnergyIsTheSwitchedCapacitanceTimesTheCubeOfTheSpeedTimesTheTime() {
		Mvtora model = model();
		Mvtora.Task task = new Mvtora.Task(1e6, 2, 10);
		double speed = 1.5;
		double time = model.localDelay(task, speed, 0);
		assertEquals(2 / 1.5, time, 1e-12);
		assertEquals(1e-28 * Math.pow(1.5e9, 3) * time, model.localEnergy(task, speed), 1e-9);
	}

	// ------------------------------------------------------- Algorithm 2

	@Test
	void theClosedFormAllocationMaximisesTheUtilityOfOneTask() {
		Mvtora model = model();
		Mvtora.Task task = new Mvtora.Task(2e6, 3, 1);
		double rate = 50e6;
		double best = model.optimalAllocation(task, rate, 0, Double.MAX_VALUE);
		double at = model.mecUtility(task, rate, best);
		assertTrue(at >= model.mecUtility(task, rate, best * 0.99));
		assertTrue(at >= model.mecUtility(task, rate, best * 1.01));
	}

	@Test
	void theBisectionKeepsTheAllocationWithinTheCapacityAndEachTaskWithinACore() {
		Mvtora model = model();
		List<Mvtora.Task> tasks = List.of(new Mvtora.Task(2e6, 3, 1), new Mvtora.Task(1e6, 5, 2),
				new Mvtora.Task(3e6, 1, 0.5));
		double[] rates = { 50e6, 50e6, 50e6 };
		double[] tight = model.allocate(tasks, rates, 10, 8);
		double total = 0;
		for (double f : tight) {
			assertTrue(f > 0 && f <= 8);
			total += f;
		}
		assertTrue(total <= 10 + 1e-6, "the controller gives no more than it has: " + total);
		assertTrue(total > 9.9, "and almost all of it");

		double[] ample = model.allocate(tasks, rates, 1e6, 8);
		for (int i = 0; i < tasks.size(); i++)
			assertEquals(model.optimalAllocation(tasks.get(i), rates[i], 0, 8), ample[i], 1e-9,
					"with room for all, every task gets what is best for it");
	}

	// ------------------------------------------------------- Algorithm 1

	@Test
	void theGameOffloadsToTheControllerOnlyThoseThatGainByIt() {
		Mvtora model = model();
		Mvtora.Task task = new Mvtora.Task(2e6, 4, 1);
		// Local computing of 4 gigacycles at 2 GHz takes 2 s: too late.
		double local = model.localUtility(task, 2, 0);
		Mvtora.Player slow = new Mvtora.Player(task, 50e6, local);
		Mvtora.Outcome ample = model.play(List.of(slow, slow, slow), 60, 20);
		for (boolean mec : ample.mec)
			assertTrue(mec, "with room on the controller, every task goes there");
		assertTrue(ample.iterations <= 3, "and the game settles at once: " + ample.iterations);

		Mvtora.Outcome none = model.play(List.of(slow, slow), 0, 20);
		assertFalse(none.mec[0] || none.mec[1], "a controller with no idle core takes nothing");

		// Room for one task in time: the second one is better off with its own way.
		Mvtora.Player fast = new Mvtora.Player(task, 50e6, model.utility(task, 0.9, 0, 0));
		Mvtora.Outcome one = model.play(List.of(fast, fast), 5, 20);
		int offloaded = (one.mec[0] ? 1 : 0) + (one.mec[1] ? 1 : 0);
		assertEquals(1, offloaded, "only one task fits on 5 GHz in time");
		for (int i = 0; i < 2; i++)
			assertTrue(one.utility[i] >= fast.initialUtility, "and no player loses by the game");
	}

	// --------------------------------------------------- Algorithms 3 and 4

	@Test
	void theFogNodesChosenAreThoseThatWouldRunTheTaskSoonest() {
		Mvtora.Task task = new Mvtora.Task(1e6, 2, 5);
		List<Mvtora.Fog> fogs = List.of(new Mvtora.Fog(10e6, 1, 0), new Mvtora.Fog(10e6, 4, 0),
				new Mvtora.Fog(10e6, 4, 3), new Mvtora.Fog(10e6, 2, 0));
		assertEquals(List.of(1, 3), Mvtora.select(task, fogs, 2));
		assertEquals(4, Mvtora.select(task, fogs, 5).size(), "fewer than K_n: all of them");
	}

	@Test
	void theGeneticAlgorithmGivesAFasterNodeALargerShare() {
		Mvtora model = model();
		Mvtora.Task task = new Mvtora.Task(1e6, 4, 5);
		List<Mvtora.Fog> twins = List.of(new Mvtora.Fog(100e6, 2, 0), new Mvtora.Fog(100e6, 2, 0));
		double[] even = model.divide(task, twins, new Random(1));
		assertEquals(1, even[0] + even[1], 1e-9, "the shares add up to one");
		assertEquals(0.5, even[0], 0.05, "two equal nodes take half each");

		List<Mvtora.Fog> uneven = List.of(new Mvtora.Fog(100e6, 1, 0), new Mvtora.Fog(100e6, 3, 0));
		double[] shares = model.divide(task, uneven, new Random(1));
		assertEquals(0.75, shares[1], 0.05, "a node three times as fast takes three quarters");
		assertTrue(model.fogDelay(task, uneven, shares) < model.fogDelay(task, uneven, new double[] { 0.5, 0.5 }),
				"which beats an even split");
	}

	@Test
	void theRatesOfTheChannelModelFallWithDistance() {
		Mvtora model = model();
		assertTrue(model.groundRate(50) > model.groundRate(500));
		assertTrue(model.airRate(50) > model.airRate(500));
		assertTrue(model.groundRate(500) > 0 && model.airRate(500) > 0);
	}

	// -------------------------------------------------------- the strategy

	@Test
	@Tag("integration")
	void onTheTestbedTheControllersDecideAndTheVehiclesTakeParts() throws Exception {
		testbed("servers.mobile_fraction", "0.3");
		String console = run(MVTORA);

		assertTrue(console.contains("MVTORA: "), "MVTORA reports what it decided");
		java.util.regex.Matcher decided = java.util.regex.Pattern
				.compile("(\\d+) local, (\\d+) MEC, (\\d+) VFC").matcher(console);
		assertTrue(decided.find());
		assertTrue(Integer.parseInt(decided.group(2)) > 0, "tasks are offloaded to the controllers");
		assertTrue(Integer.parseInt(decided.group(3)) > 0, "and to the vehicles");
		assertTrue(successRate(console) > 80, "and the tasks are served");

		List<Map<String, String>> timeline = rows(MVTORA, "timeline");
		Map<String, String> last = timeline.get(timeline.size() - 1);
		assertTrue(number(last, "mvtora_mec") > 0);
		assertTrue(number(last, "control_kbps_per_server") > 0, "its protocol costs traffic");
	}
}
