package partitiontestbed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.network.InfrastructureGraph;
import com.mechalikh.pureedgesim.network.NetworkLink;

import partitiontestbed.TestbedConfig.GroupMode;
import partitiontestbed.TestbedConfig.PartitionSpec;

/**
 * Tests the testbed itself: its configuration, what a partition and a failure do
 * to the network, the device types, the delivery modes, and that every strategy
 * faces the same conditions. The runs use {@link AccessServerStrategy}, so that
 * nothing here depends on Flocky.
 */
class TestbedTest extends TestbedTestSupport {

	private static final String ACCESS = AccessServerStrategy.NAME;

	@BeforeEach
	void registerTheTestStrategy() {
		AccessServerStrategy.register();
	}

	// ---------------------------------------------------------- configuration

	@Test
	void theFileIsReadAndTheCommandLineOverridesIt() {
		testbed("edge_nodes", "33");
		testbed("partition.1.cloud", "none");
		testbed("services.3.traits", "ATTESTATION");
		TestbedConfig config = new TestbedConfig(TEST_SETTINGS + "testbed.properties");

		assertEquals(33, config.edgeNodes, "a testbed.* property wins over the file");
		assertEquals(0.001, config.baseLatency, 1e-12, "durations in ms are kept in seconds");
		assertTrue(config.serviceTraits(0).isEmpty(), "a stateless task asks for no trait");
		assertEquals(Set.of(Trait.ATTESTATION), config.serviceTraits(3), "an intent can still be given");
		assertTrue(config.returnResults);

		assertEquals(1, config.partitions.size());
		PartitionSpec partition = config.partitions.get(0);
		assertEquals(GroupMode.GEO, partition.mode);
		assertEquals(2, partition.groupCount);
		assertEquals(90, partition.start);
		assertEquals("none", partition.cloud);
	}

	@Test
	void anotherFileCanBeNamed() throws Exception {
		Path file = Path.of(OUTPUT_FOLDER, "other-testbed.properties");
		Files.createDirectories(file.getParent());
		Files.writeString(file, Files.readString(Path.of(TEST_SETTINGS + "testbed.properties"))
				.replace("edge_nodes=20", "edge_nodes=21"));
		testbed("config", file.toString());
		assertEquals(21, new TestbedConfig(TEST_SETTINGS + "testbed.properties").edgeNodes);
	}

	@Test
	void anExplicitPartitionNamesItsGroupsAndCanBeSwitchedOff() {
		testbed("partition.1.groups", "dc1,dc2 | dc3");
		testbed("partition.2.start_s", "10");
		testbed("partition.2.duration_s", "5");
		testbed("partition.2.groups", "random:3");
		testbed("partition.2.enabled", "false");
		TestbedConfig config = new TestbedConfig(TEST_SETTINGS + "testbed.properties");

		assertEquals(1, config.partitions.size(), "a disabled partition is skipped");
		PartitionSpec partition = config.partitions.get(0);
		assertEquals(GroupMode.EXPLICIT, partition.mode);
		assertEquals(List.of(List.of("dc1", "dc2"), List.of("dc3")), partition.explicitGroups);
	}

	@Test
	void anInvalidValueFailsBeforeTheRunStarts() {
		assertInvalid("partition.1.cloud", "west", "partition.1.cloud");
		assertInvalid("partition.1.cloud", "all", "the one group that keeps the cloud");
		assertInvalid("partition.1.cloud", "0,1", "the one group that keeps the cloud");
		assertInvalid("partition.1.groups", "geo:0", "partition.1.groups");
		assertInvalid("services.1.traits", "TELEPORTATION", "unknown trait");
		assertInvalid("traits.GREEN_ENERGY", "1.5", "traits.GREEN_ENERGY");
		assertInvalid("tasks.delivery", "sometimes", "result or fire_and_forget");
		assertInvalid("edge_nodes", "12.5", "whole number");
	}

	@Test
	void anUnknownAlgorithmNamesTheKnownOnes() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> Testbed.strategy("NOT_A_STRATEGY"));
		assertTrue(error.getMessage().contains("FLOCKY_LATENCY") && error.getMessage().contains(ACCESS),
				error.getMessage());
	}

	// -------------------------------------------------------------- network

	@Test
	void theCloudNeverRelaysTrafficBetweenTwoServers() {
		// Two servers, each with a gateway uplink to the cloud, and the mesh link
		// between them.
		ComputingNode a = mock(ComputingNode.class);
		ComputingNode b = mock(ComputingNode.class);
		ComputingNode cloud = mock(ComputingNode.class);
		InfrastructureGraph graph = new InfrastructureGraph();
		NetworkLink ab = link(graph, a, b, 0.002);
		NetworkLink ba = link(graph, b, a, 0.002);
		link(graph, a, cloud, 0.03);
		link(graph, cloud, a, 0.03);
		link(graph, b, cloud, 0.03);
		link(graph, cloud, b, 0.03);
		LiveTopology topology = new LiveTopology(graph, cloud);
		assertEquals(0.002, topology.latency(a, b), 1e-12);

		topology.cut(List.of(ab, ba), 10);
		assertFalse(topology.reachable(a, b), "a partition cuts the two servers, even though both reach the cloud");
		assertTrue(topology.reachable(a, cloud), "each one still reaches the cloud");
		assertTrue(topology.reachable(cloud, b), "and the cloud each one");
		assertTrue(topology.brokenSince(List.of(ab), 5), "a transfer sent before the cut is lost with it");

		topology.restore(List.of(ab, ba));
		assertEquals(0.002, topology.latency(a, b), 1e-12, "the cut heals");
	}

	@Test
	@Tag("integration")
	void aPartitionCutsTheLinksBetweenItsGroupsAndGivesThemBack() throws Exception {
		run(ACCESS);

		List<Map<String, String>> timeline = rows(ACCESS, "timeline");
		assertEquals(0, number(at(timeline, 80), "links_down"), "no link is down before the partition");
		assertTrue(number(at(timeline, 170), "links_down") > 0, "the links between the groups are down");
		assertEquals(0, number(at(timeline, 230), "links_down"), "and back up after it");

		List<Map<String, String>> groups = rows(ACCESS, "partitions");
		assertEquals(2, groups.size(), "geo:2 makes two groups");
		// A band of the map need not be connected on its own: on this sparse mesh, some
		// paths between two western servers ran through the east, so only part of the
		// west still reaches its gateway.
		assertTrue(number(groups.get(0), "access_nodes_with_cloud") > 0, "the west keeps the cloud");
		assertTrue(number(groups.get(0), "access_nodes_with_cloud") < number(groups.get(0), "access_nodes"),
				"but not on the access servers the cut separated from its gateway");
		assertEquals(0, number(groups.get(1), "access_nodes_with_cloud"), "the east has none");
		assertEquals(DEVICES, number(groups.get(0), "devices") + number(groups.get(1), "devices"),
				"every device is in the group of its access server");
	}

	@Test
	@Tag("integration")
	void aGroupWithoutAccessServersHoldsNoDevice() throws Exception {
		testbed("access_nodes", "dc1,dc2,dc3");
		testbed("partition.1.groups", "dc1,dc2,dc3|dc4,dc5,dc6");
		testbed("partition.1.cloud", "1");
		run(ACCESS);

		List<Map<String, String>> groups = rows(ACCESS, "partitions");
		assertEquals(3, groups.size(), "the two named groups and the servers not named");
		assertEquals(DEVICES, number(groups.get(0), "devices"), "every device is on dc1 to dc3");
		assertEquals(0, number(groups.get(1), "devices"), "the group that keeps the cloud has no device");
		assertEquals(0, number(groups.get(2), "devices"));
		assertEquals(0, number(groups.get(0), "access_nodes_with_cloud"), "and the devices have no cloud");
		assertEquals(0, number(groups.get(1), "tasks_ok") + number(groups.get(1), "tasks_failed"),
				"a group without devices runs no task");
	}

	@Test
	@Tag("integration")
	void aTransferOnALinkThatGoesDownIsLostAsANetworkOutage() throws Exception {
		// The access server of a device is also its host here, so only its own link
		// carries its tasks: servers that fail cut it.
		testbed("partition.1.enabled", "false");
		testbed("failures.node_failure_rate_per_hour", "30");
		testbed("failures.node_mean_repair_s", "20");
		String console = run(ACCESS);

		assertTrue(rows(ACCESS, "failures").stream()
				.anyMatch(row -> "FAILED_DUE_TO_NETWORK_OUTAGE".equals(row.get("reason"))),
				"tasks are lost with their link, and each one is in the failures file");
		assertEquals(reported(console, "failed \\("), rows(ACCESS, "failures").size(),
				"every failed task is in the failures file");
		assertTrue(rows(ACCESS, "timeline").stream().anyMatch(row -> number(row, "links_down") > 0),
				"links went down");
	}

	// -------------------------------------------------------------- devices

	@Test
	@Tag("integration")
	void everyDeviceTypeCallsOnlyItsOwnApplication() throws Exception {
		testbed("partition.1.enabled", "false");
		String console = run(ACCESS);

		Map<String, Set<String>> applications = new HashMap<>();
		for (Map<String, String> row : rows(ACCESS, "deployments"))
			applications.computeIfAbsent(row.get("device_type"), type -> new HashSet<>()).add(row.get("service"));
		assertEquals(Map.of("IoT sensor", Set.of("Sensor telemetry"), "Security camera", Set.of("Video analytics"),
				"Smartphone", Set.of("AR assistant"), "Payment terminal", Set.of("Payment authorization")),
				applications, "the application of a device is the one its type names in edge_devices.xml");

		List<Map<String, String>> types = rows(ACCESS, "device_types");
		assertEquals(4, types.size());
		assertEquals(DEVICES, types.stream().mapToDouble(row -> number(row, "devices")).sum(), 1e-9,
				"every device has a type");
		Map<String, String> sensors = types.get(0);
		assertEquals("IoT sensor", sensors.get("device_type"));
		assertEquals(number(sensors, "tasks_ok"), number(sensors, "served_by_edge") + number(sensors, "served_by_cloud"),
				1e-9, "a sensor has no processor, so every task it sends is served by an edge server or the cloud");
		assertFalse(console.contains("FAILED_DUE_TO_NETWORK_OUTAGE"), "nothing is lost on a whole network");
	}

	@Test
	@Tag("integration")
	void aFireAndForgetTaskIsDoneWhereItRunsAndNoResultCrossesTheNetwork() throws Exception {
		testbed("tasks.delivery", "fire_and_forget");
		String console = run(ACCESS);

		assertTrue(console.contains("fire and forget"));
		assertEquals(0, reported(console, "results reached their device"), "the devices expect nothing back");
		assertEquals(0, reported(console, "lost with their result"), "so no task can be lost on the way back");
		assertTrue(rows(ACCESS, "failures").stream().noneMatch(row -> "result".equals(row.get("lost_on"))));
		assertTrue(Files.exists(Path.of(TEST_OUTPUT, "testbed_" + ACCESS + "_40_devices_fire_and_forget_timeline.csv")),
				"the files are named after the mode");
	}

	// ------------------------------------------------------------- fairness

	@Test
	@Tag("integration")
	void everyStrategyFacesTheSamePartitionsAndTheSameFailures() throws Exception {
		testbed("partition.1.groups", "random:3");
		testbed("partition.1.cloud", "random");
		testbed("failures.link_failure_rate_per_hour", "20");
		String access = run(ACCESS);
		String flocky = run("FLOCKY_LATENCY");

		assertEquals(groupsOf(rows(ACCESS, "partitions")), groupsOf(rows("FLOCKY_LATENCY", "partitions")),
				"the same random groups, and the same group keeps the cloud, whatever the strategy drew");
		assertEquals(failuresLine(access), failuresLine(flocky), "the same random failures");
		assertEquals(column(rows(ACCESS, "timeline"), "links_down"),
				column(rows("FLOCKY_LATENCY", "timeline"), "links_down"), "at the same times");
	}

	// -------------------------------------------------------------- helpers

	private void assertInvalid(String key, String value, String message) {
		testbed(key, value);
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> new TestbedConfig(TEST_SETTINGS + "testbed.properties"));
		assertTrue(error.getMessage().contains(message), error.getMessage());
		System.clearProperty(TestbedConfig.PREFIX + key);
	}

	private static List<String> groupsOf(List<Map<String, String>> groups) {
		return groups.stream().map(row -> row.get("members") + " cloud:" + row.get("access_nodes_with_cloud"))
				.collect(Collectors.toList());
	}

	/** @return a column over the four scheduled minutes, before the run waits for the last tasks */
	private static List<String> column(List<Map<String, String>> rows, String column) {
		return rows.stream().filter(row -> number(row, "time_s") <= 240).map(row -> row.get(column))
				.collect(Collectors.toList());
	}

	private static String failuresLine(String console) {
		return console.lines().filter(line -> line.contains("random failures:")).findFirst()
				.orElseThrow(() -> new AssertionError("no random failures line"));
	}

	private static NetworkLink link(InfrastructureGraph graph, ComputingNode from, ComputingNode to,
			double latency) {
		NetworkLink link = mock(NetworkLink.class);
		when(link.getSrc()).thenReturn(from);
		when(link.getDst()).thenReturn(to);
		when(link.getLatency()).thenReturn(latency);
		graph.addLink(link);
		return link;
	}
}
