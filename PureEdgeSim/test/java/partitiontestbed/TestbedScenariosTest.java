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
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.network.InfrastructureGraph;
import com.mechalikh.pureedgesim.network.NetworkLink;
import com.mechalikh.pureedgesim.simulationmanager.Simulation;
import com.mechalikh.pureedgesim.simulationmanager.SimulationAbstract;

/**
 * Tests what lets one run hold a whole scenario: delivery modes per service,
 * devices that attach to another access server, the hooks a strategy has for
 * every task, the edge pipeline, what moves, and the replications of a sweep.
 */
class TestbedScenariosTest extends TestbedTestSupport {

	private static final String ACCESS = AccessServerStrategy.NAME;
	private static final String UAV_SETTINGS = "PureEdgeSim/testbed/scenarios/uav/";
	private static final int UAV_DEVICES = 30;
	private static final String CONVOY_SETTINGS = "PureEdgeSim/testbed/scenarios/convoy/";
	private static final int CONVOY_DEVICES = 30;

	@BeforeEach
	void registerTheTestStrategies() {
		AccessServerStrategy.register();
		RoutingStrategies.register();
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
		assertTrue(parameters.contains("orchestration_algorithms=FLOCKY_LATENCY,FLOCKY_SCORED"));

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
		labelDelivery = "mixed";
		String console = run(ACCESS);

		assertTrue(console.contains("delivery set per service"));
		double sensors = 0;
		double others = 0;
		for (Map<String, String> type : rows(ACCESS, "device_types")) {
			double tasks = number(type, "tasks_ok") + number(type, "tasks_failed");
			if ("IoT sensor".equals(type.get("device_type")))
				sensors += tasks;
			else
				others += tasks;
		}
		assertTrue(sensors > 0 && others > 0);
		assertEquals(others, reported(console, "results reached their device"), 1e-9,
				"every task brings its result back, except the telemetry of the sensors");
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
		int failedWhenMoving = reported(moving, "failed \\(");
		testbed("devices.failover", "false");
		String waiting = run(ACCESS);

		assertTrue(reported(moving, "devices attached to another access server") > 0);
		assertEquals(0, reported(waiting, "devices attached to another access server"),
				"without failover a device waits for its server");
		assertTrue(failedWhenMoving < reported(waiting, "failed \\("),
				"a device that finds another server loses fewer tasks");
	}

	// ---------------------------------------------------------------- hooks

	@Test
	@Tag("integration")
	void aStrategyCanSendEveryTaskWhereItWants() throws Exception {
		testbed("partition.1.enabled", "false");
		run(RoutingStrategies.TO_CLOUD);

		for (Map<String, String> type : rows(RoutingStrategies.TO_CLOUD, "device_types")) {
			assertTrue(number(type, "tasks_ok") > 0);
			assertEquals(number(type, "tasks_ok"), number(type, "served_by_cloud"), 1e-9,
					"the service is placed on the access server, and every task routed to the cloud");
		}
	}

	@Test
	@Tag("integration")
	void aStrategyDecidesWhetherAFailedTaskEndsThePlacement() throws Exception {
		testbed("partition.1.enabled", "false");
		testbed("devices.failover", "false");
		testbed("failures.node_failure_rate_per_hour", "30");
		testbed("failures.node_mean_repair_s", "20");
		run(ACCESS);
		run(RoutingStrategies.KEEP);

		assertTrue(rows(ACCESS, "deployments").size() > DEVICES,
				"by default a failed task ends the placement, and the next one deploys again");
		assertEquals(DEVICES, rows(RoutingStrategies.KEEP, "deployments").size(),
				"a strategy that keeps it deploys once per device");
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
			assertTrue(number(uavs, "tasks_ok") > 0, "the pipeline runs");
			tracks += number(uavs, "tasks_ok");
			List<Map<String, String>> timeline = rows(output.resolve(label + replication + "_timeline.csv"));
			assertTrue(number(timeline.get(timeline.size() - 1), "handovers") > 0,
					"devices that move attach to other access servers");
		}
		// Of the two stages of the pipeline only the last returns something to the UAV.
		List<String> results = console.lines().filter(line -> line.contains("results reached their device"))
				.collect(Collectors.toList());
		assertEquals(2, results.size(), "one report per replication");
		double returned = results.stream().mapToInt(line -> reported(line, "results reached their device")).sum();
		double crews = 0;
		for (int replication = 0; replication < 2; replication++) {
			Map<String, String> crew = rows(output.resolve(label + replication + "_device_types.csv")).get(2);
			crews += number(crew, "tasks_ok") + number(crew, "tasks_failed");
		}
		assertTrue(returned > 0 && returned < crews + tracks,
				"the detections stay on the edge: fewer results return than tasks succeed");

		assertTrue(summary.stream().anyMatch(row -> row.startsWith(ACCESS + "," + UAV_DEVICES + ",mixed,success_pct,2,")),
				"the summary averages the two replications: " + summary);
		assertTrue(Files.exists(output.resolve("testbed_summary.csv")));
	}

	@Test
	@Tag("integration")
	void theConvoyScenarioKeepsServingWhileMostOfItsEdgeServersMove() throws Exception {
		Path output = Path.of(TEST_OUTPUT, "convoy");
		Files.createDirectories(output);
		String parameters = Files.readString(Testbed.simulationParameters(CONVOY_SETTINGS))
				.replaceAll("(?m)^orchestration_algorithms=.*$", "orchestration_algorithms=" + ACCESS)
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
		String console = captured.toString(StandardCharsets.UTF_8);
		assertFalse(console.contains("unexpected error"), console);

		String label = "testbed_" + ACCESS + "_" + CONVOY_DEVICES + "_devices_mixed";
		List<Map<String, String>> types = rows(output.resolve(label + "_device_types.csv"));
		assertEquals(List.of("Scout UAV", "Mapping UAV", "Ground sensor"),
				types.stream().map(row -> row.get("device_type")).collect(Collectors.toList()));
		for (Map<String, String> type : types)
			assertTrue(number(type, "tasks_ok") > 0, type.get("device_type") + " is served");
		// Only the last of the three stages of the scouts' pipeline returns anything:
		// the mapping pipeline and the telemetry are fire and forget.
		double scoutTasks = number(types.get(0), "tasks_ok") + number(types.get(0), "tasks_failed");
		int returned = reported(console, "results reached their device");
		assertTrue(returned > 0 && returned <= scoutTasks / 3 + 1, returned + " results for " + scoutTasks
				+ " scout tasks");
		for (Map<String, String> group : rows(output.resolve(label + "_partitions.csv")))
			assertEquals(0, number(group, "access_nodes_with_cloud"), "no group keeps the cloud");
		List<Map<String, String>> timeline = rows(output.resolve(label + "_timeline.csv"));
		assertTrue(number(timeline.get(timeline.size() - 1), "handovers") > 0,
				"devices follow the servers that move");
	}

	// -------------------------------------------------------------- helpers

	private static List<String> tasksByType(List<Map<String, String>> types) {
		return types.stream()
				.map(row -> row.get("device_type") + ":" + (number(row, "tasks_ok") + number(row, "tasks_failed")))
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
