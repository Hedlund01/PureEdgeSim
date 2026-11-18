package partitiontestbed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.network.InfrastructureGraph;
import com.mechalikh.pureedgesim.network.NetworkLink;
import com.mechalikh.pureedgesim.taskgenerator.DefaultTask;

import partitiontestbed.TestbedConfig.GroupMode;
import partitiontestbed.TestbedConfig.PartitionSpec;
import partitiontestbed.baselines.AccessServerStrategy;
import partitiontestbed.baselines.CloudOnlyStrategy;
import partitiontestbed.baselines.ReplicatedStrategy;

/**
 * Tests the testbed itself: its configuration, what a partition and a failure do
 * to the network, how a task is judged, the messages of a strategy, and that
 * every strategy faces the same conditions and the same tasks. The runs use the
 * baseline strategies, so that nothing here depends on Flocky.
 */
class TestbedTest extends TestbedTestSupport {

	private static final String ACCESS = AccessServerStrategy.NAME;

	@BeforeEach
	void registerTheTestStrategies() {
		MessengerStrategy.register();
	}

	// ---------------------------------------------------------- configuration

	@Test
	void theFileIsReadAndTheCommandLineOverridesIt() {
		testbed("edge_nodes", "33");
		testbed("partition.1.cloud", "none");
		testbed("services.3.traits", "ATTESTATION");
		testbed("services.1.requirement", "lossless");
		testbed("services.1.delivery_window_s", "90");
		testbed("services.2.memory_mb", "128");
		TestbedConfig config = new TestbedConfig(TEST_SETTINGS + "testbed.properties");

		assertEquals(33, config.edgeNodes, "a testbed.* property wins over the file");
		assertEquals(0.001, config.baseLatency, 1e-12, "durations in ms are kept in seconds");
		assertTrue(config.serviceTraits(0).isEmpty(), "a stateless task asks for no trait");
		assertEquals(Set.of(Trait.ATTESTATION), config.serviceTraits(3), "an intent can still be given");
		assertTrue(config.returnResults);
		assertFalse(config.lossless(0), "a task must be on time unless its service says otherwise");
		assertTrue(config.lossless(1));
		assertEquals(90, config.deliveryWindow(1), 1e-12);
		assertEquals(60, config.deliveryWindow(0), 1e-12, "the window of the common file");
		assertEquals(128, config.instanceMemory(2), 1e-12);
		assertEquals(0.5, config.instanceStartup(2), 1e-12, "startup in ms is kept in seconds");
		assertFalse(config.nodeCrash, "a failed server is cut off unless crash is asked for");

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
		assertInvalid("services.1.requirement", "sometimes", "deadline or lossless");
		assertInvalid("traits.GREEN_ENERGY", "1.5", "traits.GREEN_ENERGY");
		assertInvalid("tasks.delivery", "sometimes", "result or fire_and_forget");
		assertInvalid("failures.node_failure_mode", "explode", "isolate or crash");
		assertInvalid("edge_nodes", "12.5", "whole number");
	}

	@Test
	void anUnknownAlgorithmNamesTheKnownOnes() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> Testbed.strategy("NOT_A_STRATEGY"));
		for (String name : List.of("FLOCKY_LATENCY", ACCESS, CloudOnlyStrategy.NAME, "RETRY", ReplicatedStrategy.NAME))
			assertTrue(error.getMessage().contains(name), error.getMessage());
	}

	// ------------------------------------------------------------ judging

	@Test
	void aLosslessServiceCountsALateTaskAndADeadlineServiceDoesNot() {
		Service deadline = new Service(0, "AR", Set.of(), true, false, false, 60, Double.NaN, 512, 0.5);
		Service lossless = new Service(1, "Telemetry", Set.of(), true, false, true, 60, 99, 512, 0.5);
		ComputingNode device = mock(ComputingNode.class);
		ComputingNode host = mock(ComputingNode.class);
		when(host.getType()).thenReturn(com.mechalikh.pureedgesim.scenariomanager.SimulationParameters.TYPES.EDGE_DATACENTER);

		Job late = job(deadline, device, 0, 1);
		late.done(5, new Attempt(late, host, host));
		assertEquals(Job.Outcome.LATE, late.outcome(100), "done after its deadline, within the window");
		assertFalse(late.met(100), "a deadline service needs it on time");
		Job lateLossless = job(lossless, device, 0, 1);
		lateLossless.done(5, new Attempt(lateLossless, host, host));
		assertTrue(lateLossless.met(100), "a lossless one only needs it done");

		Job onTime = job(deadline, device, 10, 1);
		onTime.done(10.5, new Attempt(onTime, host, host));
		assertEquals(Job.Outcome.ON_TIME, onTime.outcome(100));
		assertTrue(onTime.met(100));

		Job tooLate = job(lossless, device, 0, 1);
		tooLate.done(70, new Attempt(tooLate, host, host));
		assertEquals(Job.Outcome.LOST, tooLate.outcome(100), "done after its window is as good as lost");
		assertFalse(tooLate.met(100));

		Job never = job(lossless, device, 20, 1);
		assertEquals(Job.Outcome.LOST, never.outcome(100), "not done, and its window ran out");
		Job unknown = job(lossless, device, 50, 1);
		assertEquals(Job.Outcome.CENSORED, unknown.outcome(100), "not done, but its window outlasts the run");
		assertNull(unknown.met(100), "so it is not judged");
	}

	private static Job job(Service service, ComputingNode device, double created, double deadline) {
		DefaultTask task = new DefaultTask((int) (created * 1000));
		task.setMaxLatency(deadline);
		return new Job(task, device, service, created, false);
	}

	@Test
	void noStrategyOfTheTestbedSeesTheNetworkAsTheTestbedDoes() throws IOException {
		Path strategies = Path.of("PureEdgeSim/testbed/java/partitiontestbed");
		List<Path> files;
		try (Stream<Path> walk = Files.walk(strategies)) {
			files = walk.filter(file -> file.toString().endsWith(".java")
					&& (file.getParent().endsWith("baselines") || file.getParent().endsWith("flocky")))
					.collect(Collectors.toList());
		}
		assertTrue(files.size() >= 8, "the baselines and Flocky: " + files);
		for (Path file : files) {
			String source = Files.readString(file);
			for (String oracle : List.of("LiveTopology", "TestbedWorld", "topology()", "TaskEngine"))
				assertFalse(source.contains(oracle), file + " uses " + oracle
						+ ": a strategy learns about the network only through its own messages");
		}
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
	void aMessageAcrossAPartitionNeverArrivesAndOneSentAfterItDoes() throws Exception {
		String console = run(MessengerStrategy.NAME);
		assertFalse(console.contains("unexpected error"), console);

		assertEquals(1, MessengerStrategy.delivered.size(), "only one of the two messages arrives");
		double[] arrival = MessengerStrategy.delivered.get(0);
		assertEquals(200, arrival[0], 1e-9, "the one sent after the partition healed");
		assertTrue(arrival[1] > 200, "after its transfer time: " + arrival[1]);
		assertEquals(List.of(120.0), MessengerStrategy.timedOut, "the call across the cut times out");
		assertEquals(List.of(200.0), MessengerStrategy.replied, "the call after it gets its reply");
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
		assertEquals(0, number(groups.get(1), "tasks"), "a group without devices has no task");
	}

	@Test
	@Tag("integration")
	void aServerThatFailsLosesTheTasksOnItsLinks() throws Exception {
		// The access server of a device is also its host here, so only its own link
		// carries its tasks: servers that fail cut it.
		testbed("partition.1.enabled", "false");
		testbed("failures.node_failure_rate_per_hour", "30");
		testbed("failures.node_mean_repair_s", "20");
		String console = run(ACCESS);

		List<Map<String, String>> tasks = rows(ACCESS, "tasks");
		assertTrue(outcomes(tasks, "lost") > 0, "tasks are lost with the link of their server");
		assertTrue(exposure(console, "host_failed")[0] > 0, "and they are marked as having met a failed host");
		assertTrue(rows(ACCESS, "timeline").stream().anyMatch(row -> number(row, "links_down") > 0),
				"links went down");
	}

	@Test
	@Tag("integration")
	void aCrashedHostLosesWhatRanOnItAndACopyElsewhereSavesIt() throws Exception {
		testbed("partition.1.enabled", "false");
		testbed("failures.node_failure_rate_per_hour", "30");
		testbed("failures.node_mean_repair_s", "20");
		testbed("failures.node_failure_mode", "crash");
		String single = run(ACCESS);
		String copies = run(ReplicatedStrategy.NAME);

		double[] alone = exposure(single, "host_failed");
		double[] replicated = exposure(copies, "host_failed");
		assertTrue(alone[0] > 0 && replicated[0] > 0, "hosts crash under both");
		assertTrue(replicated[1] > alone[1],
				"a copy on a neighbour saves tasks whose host crashed: " + alone[1] + "% -> " + replicated[1] + "%");
		List<Map<String, String>> tasks = rows(ReplicatedStrategy.NAME, "tasks");
		assertTrue(tasks.stream().mapToDouble(row -> number(row, "duplicates")).sum() > 0,
				"and the second result of a task is a duplicate");
		assertEquals(rows(ACCESS, "tasks").size(), tasks.size(), "which counts once: the same tasks are judged");
	}

	// -------------------------------------------------------------- devices

	@Test
	@Tag("integration")
	void everyDeviceTypeCallsOnlyItsOwnApplication() throws Exception {
		testbed("partition.1.enabled", "false");
		run(ACCESS);

		Map<String, Set<String>> applications = new HashMap<>();
		for (Map<String, String> row : rows(ACCESS, "tasks"))
			applications.computeIfAbsent(row.get("device_type"), type -> new HashSet<>()).add(row.get("service"));
		assertEquals(Map.of("IoT sensor", Set.of("Sensor telemetry"), "Security camera", Set.of("Video analytics"),
				"Smartphone", Set.of("AR assistant"), "Payment terminal", Set.of("Payment authorization")),
				applications, "the application of a device is the one its type names in edge_devices.xml");

		List<Map<String, String>> types = rows(ACCESS, "device_types");
		assertEquals(4, types.size());
		assertEquals(DEVICES, types.stream().mapToDouble(row -> number(row, "devices")).sum(), 1e-9,
				"every device has a type");
		for (Map<String, String> type : types)
			assertEquals(0, number(type, "lost_pct"), 1e-9, "nothing is lost on a whole network: " + type);
	}

	@Test
	@Tag("integration")
	void aFireAndForgetTaskIsDoneWhereItRuns() throws Exception {
		testbed("tasks.delivery", "fire_and_forget");
		String console = run(ACCESS);

		assertTrue(console.contains("fire and forget"));
		List<Map<String, String>> tasks = rows(ACCESS, "tasks");
		assertTrue(tasks.stream().allMatch(row -> row.get("host").isEmpty() == row.get("done_s").isEmpty()),
				"a task is done where it ran, and nothing returns to the device");
		assertTrue(Files.exists(Path.of(TEST_OUTPUT, "testbed_" + ACCESS + "_40_devices_fire_and_forget_timeline.csv")),
				"the files are named after the mode");
	}

	// ------------------------------------------------------------- fairness

	@Test
	@Tag("integration")
	void everyStrategyFacesTheSamePartitionsTheSameFailuresAndTheSameTasks() throws Exception {
		testbed("partition.1.groups", "random:3");
		testbed("partition.1.cloud", "random");
		testbed("failures.link_failure_rate_per_hour", "20");
		String access = run(ACCESS);
		String cloud = run(CloudOnlyStrategy.NAME);

		assertEquals(groupsOf(rows(ACCESS, "partitions")), groupsOf(rows(CloudOnlyStrategy.NAME, "partitions")),
				"the same random groups, and the same group keeps the cloud, whatever the strategy drew");
		assertEquals(failuresLine(access), failuresLine(cloud), "the same random failures");
		assertEquals(column(rows(ACCESS, "timeline"), "links_down"),
				column(rows(CloudOnlyStrategy.NAME, "timeline"), "links_down"), "at the same times");
		assertEquals(tasksOf(rows(ACCESS, "tasks")), tasksOf(rows(CloudOnlyStrategy.NAME, "tasks")),
				"and the same tasks, judged however the strategy fared");
		assertTrue(successRate(cloud) < successRate(access), "a strategy that needs the cloud loses more in a cut");
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

	private static Set<String> tasksOf(List<Map<String, String>> tasks) {
		return tasks.stream().map(row -> row.get("task") + "@" + row.get("device") + ":" + row.get("created_s"))
				.collect(Collectors.toSet());
	}

	/** @return a column over the four scheduled minutes */
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
