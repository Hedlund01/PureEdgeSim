package partitiontestbed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.network.InfrastructureGraph;
import com.mechalikh.pureedgesim.network.NetworkLink;
import com.mechalikh.pureedgesim.simulationmanager.Simulation;
import com.mechalikh.pureedgesim.simulationmanager.SimulationAbstract;

import partitiontestbed.baselines.AccessServerStrategy;
import partitiontestbed.baselines.CloudOnlyStrategy;
import partitiontestbed.baselines.RetryStrategy;

/**
 * Tests what lets one run hold a whole scenario: delivery modes per service,
 * devices that attach to another access server, the hooks a strategy has for
 * every task, the edge pipeline, what moves, and the replications of a sweep.
 */
class TestbedScenariosTest extends TestbedTestSupport {

	private static final String ACCESS = AccessServerStrategy.NAME;
	private static final String RETRY = RetryStrategy.NAME;
	private static final String UAV_SETTINGS = "PureEdgeSim/testbed/scenarios/uav/";
	private static final int UAV_DEVICES = 30;
	private static final String CONVOY_SETTINGS = "PureEdgeSim/testbed/scenarios/convoy/";
	private static final int CONVOY_DEVICES = 30;

	@BeforeEach
	void registerTheTestStrategies() {
		GroupStrategies.register();
	}

	// ------------------------------------------------------------ scenarios

	@Test
	void everythingIsAScenarioAndTheDefaultIsOneOfThem() {
		assertTrue(Testbed.scenarios().containsAll(List.of("default", "uav", "convoy")), Testbed.scenarios().toString());
		assertEquals("default", Testbed.scenario());
		assertEquals(Testbed.SCENARIOS + "default/", Testbed.settingsFolder());
		assertTrue(Testbed.outputFolder().endsWith("output/default/"));

		testbed("scenario", "uav");
		assertEquals(Testbed.SCENARIOS + "uav/", Testbed.settingsFolder());
		assertEquals(Testbed.SCENARIOS + "uav/testbed.properties", Testbed.settingsFile("testbed.properties"));
		assertTrue(Testbed.outputFolder().endsWith("output/uav/"));

		testbed("scenario", "nowhere");
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, Testbed::settingsFolder);
		assertTrue(error.getMessage().contains("convoy"), error.getMessage());
	}

	@Test
	void whatAScenarioDoesNotSetIsReadFromTheCommonFileAndTheCommandLineReplacesBoth() throws Exception {
		testbed("scenario", "uav");
		TestbedConfig uav = new TestbedConfig(Testbed.settingsFile("testbed.properties"));
		assertEquals(30, uav.edgeNodes, "the edge layer is the scenario's");
		assertEquals(0.001, uav.baseLatency, 1e-12, "the latency of the mesh is common to all scenarios");
		assertEquals(1.0 / 3600, uav.nodeFailureRate, 1e-12, "a scenario can replace a common key");
		assertEquals(0.5 / 3600, uav.linkFailureRate, 1e-12);
		assertFalse(Files.exists(Path.of(Testbed.settingsFile("flocky.properties"))),
				"the scenario has no Flocky file: every key of Flocky is common");
		assertEquals(3, new partitiontestbed.flocky.FlockyConfig(Testbed.settingsFile("flocky.properties"), 30)
				.discoveryInterval, 1e-12);

		List<String> parameters = Files.readAllLines(Testbed.simulationParameters(Testbed.settingsFolder()));
		assertTrue(parameters.contains("simulation_time=12"), "the duration is the scenario's");
		assertTrue(parameters.contains("wan_bandwidth=200"), "the network of the framework is common");
		assertTrue(parameters.contains("orchestration_algorithms=FLOCKY_LATENCY,FLOCKY_SCORED,MVTORA"));

		testbed("mesh.base_latency_ms", "2");
		System.setProperty("simulation.simulation_time", "5");
		try {
			assertEquals(0.002, new TestbedConfig(Testbed.settingsFile("testbed.properties")).baseLatency, 1e-12);
			assertTrue(Files.readAllLines(Testbed.simulationParameters(Testbed.settingsFolder()))
					.contains("simulation_time=5"), "the command line replaces the files");
		} finally {
			System.clearProperty("simulation.simulation_time");
		}
	}

	// -------------------------------------------------------------- network

	@Test
	void aFailedServerTakesItsLinksAndADisabledRadioLinkCarriesNothing() {
		// A device with a radio link to each of two servers, joined by a mesh link.
		ComputingNode device = mock(ComputingNode.class);
		ComputingNode a = mock(ComputingNode.class);
		ComputingNode b = mock(ComputingNode.class);
		ComputingNode cloud = mock(ComputingNode.class);
		InfrastructureGraph graph = new InfrastructureGraph();
		NetworkLink toA = link(graph, device, a);
		link(graph, a, device);
		NetworkLink toB = link(graph, device, b);
		NetworkLink fromB = link(graph, b, device);
		NetworkLink ab = link(graph, a, b);
		link(graph, b, a);
		LiveTopology topology = new LiveTopology(graph, cloud);

		topology.enable(toB, false, 0);
		topology.enable(fromB, false, 0);
		topology.cut(List.of(ab), 1);
		assertFalse(topology.reachable(a, b), "the device is attached to one server, and relays nothing to the other");
		assertEquals(1, topology.linksDown(), "a radio link out of use is not a link down");
		topology.restore(List.of(ab));

		topology.fail(a, 5);
		assertFalse(topology.reachable(device, b), "the server of the device failed, with every link it has");
		assertFalse(topology.isUp(toA));
		assertTrue(topology.brokenSince(List.of(toA), 2), "what was on its way to it is lost");
		topology.enable(toA, false, 5);
		topology.enable(toB, true, 5);
		topology.enable(fromB, true, 5);
		assertTrue(topology.reachable(device, b), "the device reaches the network again through the other server");

		topology.repair(a);
		assertTrue(topology.reachable(b, a), "the server answers again");
		assertEquals(0, topology.linksDown());
	}

	// ------------------------------------------------------------- delivery

	@Test
	@Tag("integration")
	void oneRunMixesTasksThatReturnAResultAndTasksThatDoNot() throws Exception {
		testbed("partition.1.enabled", "false");
		testbed("services.1.delivery", "fire_and_forget");
		testbed("services.1.requirement", "lossless");
		labelDelivery = "mixed";
		String console = run(ACCESS);

		assertTrue(console.contains("delivery set per service"));
		for (Map<String, String> type : rows(ACCESS, "device_types")) {
			assertTrue(number(type, "tasks") > 0, type.get("device_type") + " sends tasks");
			assertEquals("IoT sensor".equals(type.get("device_type")) ? "lossless" : "deadline", type.get("requirement"),
					"each service is judged by its own requirement");
		}
		assertTrue(console.contains("Sensor telemetry         lossless"), "and the report says which");
	}

	// ------------------------------------------------------------- fairness

	@Test
	@Tag("integration")
	void everyStrategyGetsTheSameTasksFromTheSameDevices() throws Exception {
		run(ACCESS);
		run("FLOCKY_LATENCY");

		assertEquals(tasksByType(rows(ACCESS, "device_types")), tasksByType(rows("FLOCKY_LATENCY", "device_types")),
				"the same devices send the same tasks, whatever the strategy");
	}

	// ----------------------------------------------------------- attachment

	@Test
	@Tag("integration")
	void aDeviceWhoseAccessServerFailsAttachesToAnotherOneInRange() throws Exception {
		testbed("partition.1.enabled", "false");
		testbed("failures.node_failure_rate_per_hour", "30");
		testbed("failures.node_mean_repair_s", "20");
		testbed("devices.radio_range_m", "1500");
		String moving = run(ACCESS);
		testbed("devices.failover", "false");
		String waiting = run(ACCESS);

		assertTrue(reported(moving, "devices attached to another access server") > 0);
		assertEquals(0, reported(waiting, "devices attached to another access server"),
				"without failover a device waits for its server");
		assertTrue(percent(moving, "lost") < percent(waiting, "lost"),
				"a device that finds another server loses fewer tasks");
	}

	// ---------------------------------------------------------- strategies

	@Test
	@Tag("integration")
	void aStrategyCanSendEveryTaskWhereItWants() throws Exception {
		testbed("partition.1.enabled", "false");
		run(CloudOnlyStrategy.NAME);

		for (Map<String, String> type : rows(CloudOnlyStrategy.NAME, "device_types")) {
			assertTrue(number(type, "done_on_cloud") > 0);
			assertEquals(0, number(type, "done_on_edge"), 1e-9, "every task runs on the cloud");
		}
	}

	// ------------------------------------------------- pipeline and mobility

	@Test
	@Tag("integration")
	void theUavScenarioRunsItsPipelineOnTheEdgeWhileDevicesAndServersMoveOverTwoReplications() throws Exception {
		Path output = Path.of(TEST_OUTPUT, "uav");
		Files.createDirectories(output);
		String parameters = Files.readString(Testbed.simulationParameters(UAV_SETTINGS))
				.replaceAll("(?m)^orchestration_algorithms=.*$", "orchestration_algorithms=" + ACCESS)
				.replaceAll("(?m)^simulation_time=.*$", "simulation_time=3")
				.replaceAll("(?m)^(m..)_number_of_edge_devices=.*$", "$1_number_of_edge_devices=" + UAV_DEVICES)
				.replaceAll("(?m)^edge_device_counter_size=.*$", "edge_device_counter_size=" + UAV_DEVICES);
		Path parametersFile = output.resolve("simulation_parameters.properties");
		Files.writeString(parametersFile, parameters);
		testbed("scenario", "uav");
		testbed("partition.1.start_s", "60");
		testbed("partition.1.duration_s", "60");
		testbed("partition.2.enabled", "false");

		PrintStream originalOut = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		List<String> summary;
		try {
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
			summary = Testbed.sweep(2, () -> {
				Simulation simulation = new Simulation();
				Testbed.configure(simulation);
				simulation.setCustomSettingsFolder(UAV_SETTINGS);
				simulation.setCustomFilePath(parametersFile.toString(),
						SimulationAbstract.Files.SIMULATION_PARAMETERS);
				simulation.setCustomOutputFolder(output.toString() + "/");
				return simulation;
			}, output.toString());
		} finally {
			System.setOut(originalOut);
		}
		String console = captured.toString(StandardCharsets.UTF_8);
		assertFalse(console.contains("unexpected error"), console);

		String label = "testbed_" + ACCESS + "_" + UAV_DEVICES + "_devices_mixed_rep";
		double tracks = 0;
		for (int replication = 0; replication < 2; replication++) {
			Map<String, String> uavs = rows(output.resolve(label + replication + "_device_types.csv")).get(0);
			assertEquals("UAV", uavs.get("device_type"));
			assertTrue(number(uavs, "requirement_met_pct") > 0, "the pipeline runs");
			List<Map<String, String>> tasks = rows(output.resolve(label + replication + "_tasks.csv"));
			for (String stage : List.of("detect", "track"))
				assertTrue(tasks.stream().anyMatch(row -> stage.equals(row.get("stage")) && !row.get("done_s").isEmpty()),
						"its " + stage + " stage is done on the edge");
			tracks += tasks.stream().filter(row -> "track".equals(row.get("stage"))).count();
			List<Map<String, String>> timeline = rows(output.resolve(label + replication + "_timeline.csv"));
			assertTrue(number(timeline.get(timeline.size() - 1), "handovers") > 0,
					"devices that move attach to other access servers");
		}
		assertTrue(tracks > 0, "the detections feed the tracking stage on the edge");

		assertTrue(summary.stream()
				.anyMatch(row -> row.startsWith(ACCESS + "," + UAV_DEVICES + ",mixed,requirement_met_pct,2,")),
				"the summary averages the two replications: " + summary);
		assertTrue(Files.exists(output.resolve("testbed_summary.csv")));
		assertTrue(Files.readAllLines(output.resolve("testbed_kpis.csv")).get(0).startsWith(
				"algorithm,devices,delivery,runs,requirement_met_pct,requirement_met_pct_ci95"),
				"and the KPI table has one column per KPI, the same for every algorithm");
	}

	@Test
	@Tag("integration")
	void theConvoyScenarioKeepsServingWhileMostOfItsEdgeServersMove() throws Exception {
		Path output = Path.of(TEST_OUTPUT, "convoy");
		String console = runConvoy(ACCESS, output);
		assertFalse(console.contains("unexpected error"), console);

		String label = "testbed_" + ACCESS + "_" + CONVOY_DEVICES + "_devices_mixed";
		List<Map<String, String>> types = rows(output.resolve(label + "_device_types.csv"));
		assertEquals(List.of("Scout UAV", "Mapping UAV", "Ground sensor"),
				types.stream().map(row -> row.get("device_type")).collect(Collectors.toList()));
		for (Map<String, String> type : types)
			assertTrue(number(type, "requirement_met_pct") > 0, type.get("device_type") + " is served");
		List<Map<String, String>> tasks = rows(output.resolve(label + "_tasks.csv"));
		for (String stage : List.of("detect", "fuse", "advise", "stitch", "index"))
			assertTrue(tasks.stream().anyMatch(row -> stage.equals(row.get("stage"))), "the " + stage + " stage runs");
		assertEquals("lossless", types.get(1).get("requirement"), "the map must not be lost");
		for (Map<String, String> group : rows(output.resolve(label + "_partitions.csv")))
			assertEquals(0, number(group, "access_nodes_with_cloud"), "no group keeps the cloud");
		List<Map<String, String>> timeline = rows(output.resolve(label + "_timeline.csv"));
		assertTrue(number(timeline.get(timeline.size() - 1), "handovers") > 0,
				"devices follow the servers that move");
	}

	@Test
	@Tag("integration")
	void aServerThatDriftsAwayFromItsGroupJoinsTheGroupItReachesAndNoPathCrossesTwoGroups() throws Exception {
		Path output = Path.of(TEST_OUTPUT, "convoy-open");
		String console = runConvoy(GroupStrategies.OPEN, output);
		assertFalse(console.contains("unexpected error"), console);

		List<Map<String, String>> groups = rows(output.resolve(convoyLabel(GroupStrategies.OPEN) + "_partitions.csv"));
		double serversJoined = groups.stream().mapToDouble(group -> number(group, "servers_joined")).sum();
		double serversLeft = groups.stream().mapToDouble(group -> number(group, "servers_left")).sum();
		double devicesJoined = groups.stream().mapToDouble(group -> number(group, "devices_joined")).sum();
		double devicesLeft = groups.stream().mapToDouble(group -> number(group, "devices_left")).sum();
		assertTrue(serversJoined > 0, "vehicles change group: " + groups);
		assertEquals(serversJoined, serversLeft, "every server that joins a group left another");
		assertTrue(devicesJoined > 0, "devices follow: " + groups);
		assertEquals(devicesJoined, devicesLeft, "every device that joins a group left another");
		assertTrue(console.contains("left group"), "the run reports each server that changes group");
		assertTrue(GroupStrategies.asked >= serversJoined + devicesJoined, "the strategy was asked every time");
		assertEquals(0, GroupStrategies.crossings, "no path joins two servers of different groups as they are now");
	}

	@Test
	@Tag("integration")
	void aStrategyThatAdmitsNoOneKeepsEveryNodeInItsGroup() throws Exception {
		Path output = Path.of(TEST_OUTPUT, "convoy-closed");
		String console = runConvoy(GroupStrategies.CLOSED, output);
		assertFalse(console.contains("unexpected error"), console);

		for (Map<String, String> group : rows(output.resolve(convoyLabel(GroupStrategies.CLOSED) + "_partitions.csv")))
			for (String column : List.of("servers_joined", "servers_left", "devices_joined", "devices_left"))
				assertEquals(0, number(group, column), column + " of " + group);
		Matcher refused = Pattern.compile("changed partition group, (\\d+) joins refused").matcher(console);
		assertTrue(refused.find() && Integer.parseInt(refused.group(1)) > 0, "the refusals are counted");
	}

	@Test
	@Tag("integration")
	void groupTransfersCanBeSwitchedOff() throws Exception {
		testbed("partitions.group_transfer", "false");
		Path output = Path.of(TEST_OUTPUT, "convoy-static");
		String console = runConvoy(GroupStrategies.OPEN, output);
		assertFalse(console.contains("unexpected error"), console);

		for (Map<String, String> group : rows(output.resolve(convoyLabel(GroupStrategies.OPEN) + "_partitions.csv")))
			for (String column : List.of("servers_joined", "servers_left", "devices_joined", "devices_left"))
				assertEquals(0, number(group, column), column + " of " + group);
		assertEquals(0, GroupStrategies.asked, "the strategy is never asked");
		assertEquals(0, GroupStrategies.crossings, "and no path crosses two groups");
	}

	@Test
	@Tag("integration")
	void aStrategyThatRetriesRecoversTasksWhoseDeviceWasOutOfRange() throws Exception {
		Path once = Path.of(TEST_OUTPUT, "convoy-once");
		String access = runConvoy(ACCESS, once);
		Path retried = Path.of(TEST_OUTPUT, "convoy-retry");
		String retry = runConvoy(RETRY, retried);

		double[] detachedOnce = exposure(access, "device_detached");
		double[] detachedRetried = exposure(retry, "device_detached");
		assertTrue(detachedOnce[0] > 0 && detachedRetried[0] > 0, "devices go out of range in the convoy");
		assertTrue(detachedRetried[1] > detachedOnce[1], "a device that sends again when it is back saves tasks: "
				+ detachedOnce[1] + "% -> " + detachedRetried[1] + "%");
		assertTrue(percent(retry, "lossless services") > percent(access, "lossless services"),
				"which a lossless service counts, late or not: " + percent(access, "lossless services") + "% -> "
						+ percent(retry, "lossless services") + "%");
	}

	/**
	 * Runs three minutes of the convoy scenario with one strategy, and one
	 * partition from 60 s to 120 s.
	 *
	 * @return what the run printed
	 */
	private String runConvoy(String algorithm, Path output) throws Exception {
		Files.createDirectories(output);
		String parameters = Files.readString(Testbed.simulationParameters(CONVOY_SETTINGS))
				.replaceAll("(?m)^orchestration_algorithms=.*$", "orchestration_algorithms=" + algorithm)
				.replaceAll("(?m)^simulation_time=.*$", "simulation_time=3")
				.replaceAll("(?m)^(m..)_number_of_edge_devices=.*$", "$1_number_of_edge_devices=" + CONVOY_DEVICES)
				.replaceAll("(?m)^edge_device_counter_size=.*$", "edge_device_counter_size=" + CONVOY_DEVICES);
		Path parametersFile = output.resolve("simulation_parameters.properties");
		Files.writeString(parametersFile, parameters);
		testbed("scenario", "convoy");
		testbed("partition.1.start_s", "60");
		testbed("partition.1.duration_s", "60");
		testbed("partition.2.enabled", "false");

		PrintStream originalOut = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
			Testbed.sweep(1, () -> {
				Simulation simulation = new Simulation();
				Testbed.configure(simulation);
				simulation.setCustomSettingsFolder(CONVOY_SETTINGS);
				simulation.setCustomFilePath(parametersFile.toString(),
						SimulationAbstract.Files.SIMULATION_PARAMETERS);
				simulation.setCustomOutputFolder(output.toString() + "/");
				return simulation;
			}, output.toString());
		} finally {
			System.setOut(originalOut);
		}
		return captured.toString(StandardCharsets.UTF_8);
	}

	private static String convoyLabel(String algorithm) {
		return "testbed_" + algorithm + "_" + CONVOY_DEVICES + "_devices_mixed";
	}

	// -------------------------------------------------------------- helpers

	private static List<String> tasksByType(List<Map<String, String>> types) {
		return types.stream()
				.map(row -> row.get("device_type") + ":" + (number(row, "tasks") + number(row, "tasks_censored")))
				.collect(Collectors.toList());
	}

	private static NetworkLink link(InfrastructureGraph graph, ComputingNode from, ComputingNode to) {
		NetworkLink link = mock(NetworkLink.class);
		when(link.getSrc()).thenReturn(from);
		when(link.getDst()).thenReturn(to);
		when(link.getLatency()).thenReturn(0.002);
		graph.addLink(link);
		return link;
	}
}
