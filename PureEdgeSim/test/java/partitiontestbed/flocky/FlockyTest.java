package partitiontestbed.flocky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import partitiontestbed.TestbedTestSupport;

/**
 * Tests Flocky as a strategy of the testbed: its configuration, its discovery
 * through a partition, Swirly deploying within the reach of each group, and what
 * the delivery mode does to its tasks.
 */
class FlockyTest extends TestbedTestSupport {

	private static final String FLOCKY = "FLOCKY_LATENCY";

	// ---------------------------------------------------------- configuration

	@Test
	void theFileIsReadAndTheCommandLineOverridesIt() {
		flocky("discovery.max_distance_ms", "10");
		flocky("swirly.allow_local", "false");
		FlockyConfig config = new FlockyConfig(TEST_SETTINGS + "flocky.properties", 33);

		assertEquals(0.010, config.maxDiscoveryDistance, 1e-12, "a flocky.* property wins over the file");
		assertFalse(config.allowLocal);
		assertEquals(0, config.unikernelProbability, "no unikernel, so a stateless task runs on any server");
		assertEquals(33, config.seedNodesPerDefinition, "and every server has every definition: \"all\"");
	}

	@Test
	void anInvalidValueFailsBeforeTheRunStarts() {
		assertInvalid("swirly.cloud_fallback", "maybe", "true or false");
		assertInvalid("discovery.bootstrap_peers", "40", "discovery.bootstrap_peers");
		assertInvalid("oam.seed_nodes_per_definition", "some", "not a number");
	}

	// ------------------------------------------------------------- the model

	@Test
	@Tag("integration")
	void onAWholeEdgeLayerDiscoveryFindsEveryServerInRangeAndTheTasksSucceed() throws Exception {
		testbed("partition.1.enabled", "false");
		String console = run(FLOCKY);

		List<Map<String, String>> timeline = rows(FLOCKY, "timeline");
		Map<String, String> last = timeline.get(timeline.size() - 1);
		assertEquals(1.0, number(last, "discovery_accuracy"), 1e-9, "every pair within the discovery distance is found");
		assertEquals(0.0, number(last, "stale_share"), 1e-9, "and no known server is out of reach");
		assertFalse(console.contains("FAILED_DUE_TO_NETWORK_OUTAGE"), "nothing is lost on a whole network");
		// Not every task: the half second of the AR assistant is missed now and then
		// when tasks queue on a busy edge server, which has nothing to do with the
		// network.
		assertTrue(successRate(console) > 90, "the tasks are served");
		assertTrue(console.contains("Flocky: "), "and Flocky reports what it did");
	}

	@Test
	@Tag("integration")
	void aPartitionShrinksTheViewOfEveryServerAndTheViewComesBackWhenItHeals() throws Exception {
		run(FLOCKY);

		List<Map<String, String>> timeline = rows(FLOCKY, "timeline");
		double before = number(at(timeline, 80), "known_per_node");
		double during = number(at(timeline, 170), "known_per_node");
		double after = number(at(timeline, 230), "known_per_node");
		assertTrue(during < before, "a server no longer knows the servers across the cut: " + before + " -> " + during);
		assertEquals(before, after, 1e-9, "and finds them all again once the cut heals");
		assertEquals(1.0, number(at(timeline, 230), "discovery_accuracy"), 1e-9);
	}

	@Test
	@Tag("integration")
	void duringAPartitionEveryGroupDeploysWithinItselfAndOnlyTheCloudGroupUsesTheCloud() throws Exception {
		testbed("partition.1.groups", "random:3");
		testbed("partition.1.cloud", "1");
		String console = run(FLOCKY);

		List<Map<String, String>> groups = rows(FLOCKY, "partitions");
		Map<String, Integer> groupOf = new HashMap<>();
		for (Map<String, String> group : groups)
			for (String server : group.get("members").split(" "))
				groupOf.put(server, Integer.parseInt(group.get("group")));
		for (Map<String, String> group : groups)
			if (!"1".equals(group.get("group")))
				assertEquals("0", group.get("access_nodes_with_cloud"), "only group 1 keeps the cloud");

		int during = 0;
		for (Map<String, String> deployment : rows(FLOCKY, "deployments")) {
			double time = number(deployment, "time_s");
			if (time < 90 || time >= 180)
				continue;
			during++;
			String outcome = deployment.get("outcome");
			int home = groupOf.get(deployment.get("home"));
			if (outcome.equals("EDGE") || outcome.equals("LOCAL"))
				assertEquals(home, (int) groupOf.get(deployment.get("host")),
						"a server deploys only on a server of its own group: " + deployment);
			if (outcome.equals("CLOUD"))
				assertEquals(1, home, "only the group that keeps the cloud deploys there");
			assertFalse(outcome.equals("NO_DEFINITION"), "every server has every definition");
		}
		assertTrue(during > 0, "the partition moved services");
		assertTrue(successRate(console) > 0, "and the devices kept sending");
	}

	@Test
	@Tag("integration")
	void aTaskThatMustReturnItsResultIsLostWhenTheWayBackBreaks() throws Exception {
		// The services run away from the access server, so that their results cross
		// mesh links on the way back, and the links fail often.
		flocky("swirly.allow_local", "false");
		testbed("failures.link_failure_rate_per_hour", "60");
		testbed("failures.link_mean_repair_s", "30");
		String console = run(FLOCKY);

		assertTrue(console.contains("results returned to the device"));
		assertTrue(reported(console, "results reached their device") > 0, "the results come back");
		int lostWithResult = reported(console, "lost with their result");
		assertTrue(lostWithResult > 0, "and some of the tasks that ran are lost on the way back");
		assertEquals(lostWithResult,
				rows(FLOCKY, "failures").stream().filter(row -> "result".equals(row.get("lost_on"))).count(),
				"every one of them is in the failures file");
		assertEquals(reported(console, "failed \\("), rows(FLOCKY, "failures").size(),
				"and so is every failed task");
	}

	// -------------------------------------------------------------- helpers

	private void assertInvalid(String key, String value, String message) {
		flocky(key, value);
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> new FlockyConfig(TEST_SETTINGS + "flocky.properties", 20));
		assertTrue(error.getMessage().contains(message), error.getMessage());
		System.clearProperty(FlockyConfig.PREFIX + key);
	}
}
