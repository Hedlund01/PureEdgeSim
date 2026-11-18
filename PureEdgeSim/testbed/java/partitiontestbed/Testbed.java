package partitiontestbed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.mechalikh.pureedgesim.simulationmanager.SimLog;
import com.mechalikh.pureedgesim.simulationmanager.Simulation;
import com.mechalikh.pureedgesim.simulationmanager.SimulationAbstract;

import partitiontestbed.baselines.AccessServerStrategy;
import partitiontestbed.baselines.CloudOnlyStrategy;
import partitiontestbed.baselines.ReplicatedStrategy;
import partitiontestbed.baselines.RetryStrategy;
import partitiontestbed.flocky.FlockyStrategy;
import partitiontestbed.mvtora.MvtoraStrategy;

/**
 * A testbed for decentralised orchestration on an edge-to-cloud continuum that
 * the network splits into partitions.
 * <p>
 * End devices of several types send stateless tasks to the edge servers they
 * attach to, the servers are joined by a latency mesh, and a cloud data center is
 * reached through a few gateways. The testbed breaks the network under them, with
 * random failures of links, uplinks and servers and with scheduled partitions
 * that split the edge servers into groups that cannot reach each other, at most
 * one of which keeps the cloud. The devices keep sending tasks throughout, and
 * every task is counted: where it ran, how long it took, and why it failed.
 * <p>
 * What decides where the tasks run is the {@link OrchestrationStrategy} under test,
 * selected by {@code orchestration_algorithms} in
 * {@code simulation_parameters.properties}. One run can sweep several of them, and
 * every one faces the same continuum, the same failures and the same partitions.
 * The strategies known to the testbed are registered here; a new one is a class
 * implementing {@link OrchestrationStrategy} and one more line in
 * {@link #registerDefaults()}.
 * <p>
 * The sweep is run {@code replications} times. A replication has its own
 * continuum, failures, partition groups and tasks, the same for every strategy,
 * and the figures of the runs are averaged, with their 95% confidence interval,
 * in {@code testbed_summary.csv}.
 * <p>
 * Everything a run is made of is a <b>scenario</b>: a folder of
 * {@link #SCENARIOS} holding the settings, selected by name with
 * {@code -Dtestbed.scenario}. Without it the scenario is {@link #DEFAULT}, a
 * folder like the others. A scenario kept elsewhere is named by its folder, with
 * {@code -Dtestbed.settings}.
 * <p>
 * What every scenario and every run share is in one file, {@link #COMMON}: the
 * parameters of the framework ({@code simulation.<key>}), of the testbed
 * ({@code testbed.<key>}) and of the strategies ({@code flocky.<key>}). The
 * files of a scenario hold only what it sets differently, and the command line
 * replaces both, with the same names: {@code -Dsimulation.simulation_time=5}.
 *
 * <pre>
 * mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed"
 * mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed" -Dtestbed.edge_nodes=60 -Dtestbed.tasks.delivery=fire_and_forget
 * mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed" -Dtestbed.replications=10
 * mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed" -Dtestbed.scenario=uav
 * </pre>
 *
 * @see TestbedSimulationManager
 * @see TestbedConfig
 */
public class Testbed {

	/** Folder of the scenarios of the testbed, relative to the repository root. */
	public static final String SCENARIOS = "PureEdgeSim/testbed/scenarios/";
	/** Name of the scenario that runs when none is asked for. */
	public static final String DEFAULT = "default";
	/** Folder of the default scenario. */
	public static final String SETTINGS = SCENARIOS + DEFAULT + "/";
	/** The settings every scenario and every run share. */
	public static final String COMMON = SCENARIOS + "common.properties";
	/** Prefix of the parameters of the framework in the common file and on the command line. */
	public static final String SIMULATION_PREFIX = "simulation.";
	private static final String OUTPUT = "PureEdgeSim/testbed/output/";

	private static final Map<String, Supplier<OrchestrationStrategy>> STRATEGIES = new TreeMap<>();

	/** Two-sided 95% quantiles of Student's t, by degrees of freedom from 1. */
	private static final double[] T95 = { 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
			2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060,
			2.056, 2.052, 2.048, 2.045, 2.042 };

	/** The figures of every run of the sweep in progress, by scenario. */
	private static final Map<String, List<Map<String, Double>>> RESULTS = new LinkedHashMap<>();
	private static volatile int replication;
	private static volatile int replications = 1;

	static {
		registerDefaults();
	}

	private static void registerDefaults() {
		register("FLOCKY_LATENCY", () -> new FlockyStrategy(false));
		register("FLOCKY_SCORED", () -> new FlockyStrategy(true));
		register(AccessServerStrategy.NAME, AccessServerStrategy::new);
		register(CloudOnlyStrategy.NAME, CloudOnlyStrategy::new);
		register(RetryStrategy.NAME, RetryStrategy::new);
		register(ReplicatedStrategy.NAME, ReplicatedStrategy::new);
		register(MvtoraStrategy.NAME, MvtoraStrategy::new);
	}

	/**
	 * Makes a strategy available under an algorithm name, for
	 * {@code orchestration_algorithms}.
	 *
	 * @param algorithm the name, as written in {@code simulation_parameters.properties}
	 * @param factory   creates a fresh strategy for every iteration
	 */
	public static synchronized void register(String algorithm, Supplier<OrchestrationStrategy> factory) {
		STRATEGIES.put(algorithm, factory);
	}

	/**
	 * @param algorithm an algorithm name
	 * @return a fresh strategy for it
	 * @throws IllegalArgumentException when no strategy has that name
	 */
	static synchronized OrchestrationStrategy strategy(String algorithm) {
		Supplier<OrchestrationStrategy> factory = STRATEGIES.get(algorithm);
		if (factory == null)
			throw new IllegalArgumentException("Testbed - unknown orchestration algorithm \"" + algorithm
					+ "\", expected one of " + STRATEGIES.keySet());
		return factory.get();
	}

	/**
	 * @param name the name of a file of the scenario
	 * @return its path in the folder of the scenario, where it need not exist: what
	 *         it does not set is read from the common file
	 */
	public static String settingsFile(String name) {
		return settingsFolder() + name;
	}

	/**
	 * Writes the parameters the framework reads for a scenario: those of the common
	 * file, replaced by those of {@code simulation_parameters.properties} of the
	 * scenario, replaced by the {@code -Dsimulation.<key>} of the command line.
	 *
	 * @param settingsFolder the folder of the scenario
	 * @return the file, a temporary one, for
	 *         {@link Simulation#setCustomFilePath(String, com.mechalikh.pureedgesim.simulationmanager.SimulationAbstract.Files)}
	 */
	public static Path simulationParameters(String settingsFolder) {
		Properties merged = PropertiesFile.layers(SIMULATION_PREFIX,
				new File(settingsFolder, "simulation_parameters.properties").getPath());
		List<String> lines = new ArrayList<>();
		for (String key : new TreeSet<>(merged.stringPropertyNames()))
			lines.add(key + "=" + merged.getProperty(key).trim());
		try {
			Path file = Files.createTempFile("simulation_parameters_", ".properties");
			file.toFile().deleteOnExit();
			TestbedMetrics.write(file, "# " + COMMON + " and " + settingsFolder + ", merged by the testbed", lines);
			return file;
		} catch (IOException e) {
			throw new IllegalStateException("Testbed - cannot write the simulation parameters: " + e.getMessage(), e);
		}
	}

	/**
	 * @return the folder of the scenario: {@code -Dtestbed.settings} when it names
	 *         one, the folder of {@link #scenario()} otherwise
	 * @throws IllegalArgumentException when there is no such scenario
	 */
	public static String settingsFolder() {
		String folder = System.getProperty(TestbedConfig.PREFIX + "settings", SCENARIOS + scenario());
		if (!new File(folder).isDirectory())
			throw new IllegalArgumentException("Testbed - no scenario in \"" + folder + "\", expected one of "
					+ scenarios() + " with -Dtestbed.scenario, or a folder with -Dtestbed.settings");
		return folder.endsWith("/") || folder.endsWith(File.separator) ? folder : folder + "/";
	}

	/**
	 * @return the name of the scenario: {@code -Dtestbed.scenario}, the name of the
	 *         folder {@code -Dtestbed.settings} names, or {@link #DEFAULT}
	 */
	public static String scenario() {
		String folder = System.getProperty(TestbedConfig.PREFIX + "settings");
		return folder != null ? new File(folder).getName()
				: System.getProperty(TestbedConfig.PREFIX + "scenario", DEFAULT).trim();
	}

	/** @return the names of the scenarios of {@link #SCENARIOS} */
	public static List<String> scenarios() {
		List<String> names = new ArrayList<>();
		File[] folders = new File(SCENARIOS).listFiles(File::isDirectory);
		if (folders != null)
			for (File folder : folders)
				names.add(folder.getName());
		names.sort(null);
		return names;
	}

	/**
	 * @return the output folder: {@code -Dtestbed.output}, or a folder named after
	 *         the scenario under the output folder of the testbed
	 */
	public static String outputFolder() {
		return System.getProperty(TestbedConfig.PREFIX + "output", OUTPUT + scenario() + "/");
	}

	/** @return which run of the sweep is in progress, from 0 */
	static int replication() {
		return replication;
	}

	/** @return how many times the sweep in progress is run */
	static int replications() {
		return replications;
	}

	/** Keeps the figures of a run that just ended, for the summary. */
	static synchronized void record(String scenario, Map<String, Double> values) {
		RESULTS.computeIfAbsent(scenario, key -> new ArrayList<>()).add(values);
	}

	/**
	 * Runs the sweep of the scenario, as many times as its {@code replications}
	 * asks.
	 */
	public Testbed() {
		String settings = settingsFolder();
		String output = outputFolder();
		SimLog.println("Testbed - scenario \"%s\" (%s), one of %s", scenario(), settings, scenarios());
		sweep(new TestbedConfig(settingsFile("testbed.properties")).replications, () -> {
			Simulation sim = new Simulation();
			configure(sim);
			sim.setCustomSettingsFolder(settings);
			sim.setCustomFilePath(simulationParameters(settings).toString(),
					SimulationAbstract.Files.SIMULATION_PARAMETERS);
			sim.setCustomOutputFolder(output);
			return sim;
		}, output);
	}

	/**
	 * Runs a sweep several times and averages its figures.
	 * <p>
	 * Replication {@code r} moves every seed by {@code r}: its continuum, its
	 * failures, its partition groups and its tasks are its own, and the same for
	 * every strategy of the sweep. With several replications the files of a run are
	 * named {@code ..._rep<r>_...}.
	 *
	 * @param times        the number of replications, at least one
	 * @param simulation   creates and configures the simulation of one replication
	 * @param outputFolder where {@code testbed_summary.csv} is written
	 * @return the rows of the summary, one per scenario and figure
	 */
	public static synchronized List<String> sweep(int times, Supplier<Simulation> simulation, String outputFolder) {
		RESULTS.clear();
		replications = times;
		try {
			for (replication = 0; replication < times; replication++)
				simulation.get().launchSimulation();
		} finally {
			replication = 0;
			replications = 1;
		}
		List<String> rows = summaryRows();
		try {
			TestbedMetrics.write(Path.of(outputFolder, "testbed_summary.csv"),
					"algorithm,devices,delivery,metric,runs,mean,std,ci95_low,ci95_high", rows);
			List<String> header = new ArrayList<>(List.of("algorithm", "devices", "delivery", "runs"));
			for (String kpi : KPIS) {
				header.add(kpi);
				header.add(kpi + "_ci95");
			}
			TestbedMetrics.write(Path.of(outputFolder, "testbed_kpis.csv"), String.join(",", header), kpiRows());
		} catch (IOException e) {
			SimLog.println("Testbed - cannot write the summary: %s", e.getMessage());
		}
		for (String scenario : RESULTS.keySet()) {
			String[] name = scenario.split(",");
			SimLog.println("Testbed - %s, %s devices, %s, over %d run(s): %s", name[0], name[1], name[2],
					RESULTS.get(scenario).size(), describe(scenario));
		}
		return rows;
	}

	/**
	 * The figures every algorithm is compared on in {@code testbed_kpis.csv}, one
	 * column each, with the half width of its 95% confidence interval.
	 */
	static final List<String> KPIS = List.of("requirement_met_pct", "requirement_met_partitioned_pct",
			"deadline_met_pct", "lossless_met_pct", "on_time_pct", "late_pct", "lost_pct", "delay_p50_ms",
			"delay_p95_ms", "attempts_per_task", "redundant_runs_pct", "instances_started",
			"instance_memory_mb_per_server", "control_kbps_per_server");

	/** One row per algorithm: its mean over the runs and the half width of the interval, KPI by KPI. */
	private static List<String> kpiRows() {
		List<String> rows = new ArrayList<>();
		for (Map.Entry<String, List<Map<String, Double>>> scenario : RESULTS.entrySet()) {
			StringBuilder row = new StringBuilder(scenario.getKey()).append(',').append(scenario.getValue().size());
			for (String kpi : KPIS) {
				double[] stats = statistics(scenario.getValue(), kpi);
				row.append(',').append(cell(stats[1])).append(',').append(cell(stats[3]));
			}
			rows.add(row.toString());
		}
		return rows;
	}

	/** One row per scenario and figure: its mean over the runs, and how sure it is. */
	private static List<String> summaryRows() {
		List<String> rows = new ArrayList<>();
		for (Map.Entry<String, List<Map<String, Double>>> scenario : RESULTS.entrySet()) {
			Set<String> metrics = new LinkedHashSet<>();
			for (Map<String, Double> run : scenario.getValue())
				metrics.addAll(run.keySet());
			for (String metric : metrics) {
				double[] stats = statistics(scenario.getValue(), metric);
				rows.add(String.format(Locale.ROOT, "%s,%s,%d,%s,%s,%s,%s", scenario.getKey(), metric, (int) stats[0],
						cell(stats[1]), cell(stats[2]), cell(stats[1] - stats[3]), cell(stats[1] + stats[3])));
			}
		}
		return rows;
	}

	private static String describe(String scenario) {
		StringBuilder text = new StringBuilder();
		for (String metric : List.of("requirement_met_pct", "requirement_met_partitioned_pct", "lost_pct",
				"delay_p95_ms")) {
			double[] stats = statistics(RESULTS.get(scenario), metric);
			text.append(text.length() == 0 ? "" : ", ").append(metric).append(' ').append(cell(stats[1]));
			if (!Double.isNaN(stats[3]))
				text.append(" ±").append(cell(stats[3]));
		}
		return text.toString();
	}

	/**
	 * @return the number of runs that have the figure, its mean, its standard
	 *         deviation and the half width of its 95% confidence interval; NaN
	 *         where fewer than two runs have it
	 */
	private static double[] statistics(List<Map<String, Double>> runs, String metric) {
		List<Double> values = new ArrayList<>();
		for (Map<String, Double> run : runs) {
			Double value = run.get(metric);
			if (value != null && !value.isNaN())
				values.add(value);
		}
		int n = values.size();
		if (n == 0)
			return new double[] { 0, Double.NaN, Double.NaN, Double.NaN };
		double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
		if (n == 1)
			return new double[] { 1, mean, Double.NaN, Double.NaN };
		double squares = 0;
		for (double value : values)
			squares += (value - mean) * (value - mean);
		double std = Math.sqrt(squares / (n - 1));
		double t = n - 1 <= T95.length ? T95[n - 2] : 1.96;
		return new double[] { n, mean, std, t * std / Math.sqrt(n) };
	}

	private static String cell(double value) {
		return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.3f", value);
	}

	/**
	 * Plugs every testbed class into a simulation, so that a test can run the
	 * testbed on its own settings.
	 *
	 * @param sim the simulation to configure
	 */
	public static void configure(Simulation sim) {
		sim.setCustomSimulationManager(TestbedSimulationManager.class);
		sim.setCustomComputingNode(TestbedNode.class);
		sim.setCustomComputingNodesGenerator(TestbedNodesGenerator.class);
		sim.setCustomTopologyCreator(TestbedTopologyCreator.class);
		sim.setCustomNetworkModel(TestbedNetworkModel.class);
		sim.setCustomEdgeOrchestrator(TestbedOrchestrator.class);
		sim.setCustomTaskGenerator(TestbedTaskGenerator.class);
	}

	/**
	 * Starts the testbed.
	 *
	 * @param args unused; override the configuration with {@code -Dtestbed.<key>}
	 *             and {@code -D<strategy>.<key>}
	 */
	public static void main(String[] args) {
		new Testbed();
	}
}
