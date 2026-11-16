package partitiontestbed;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;

import com.mechalikh.pureedgesim.AbstractTest;
import com.mechalikh.pureedgesim.simulationmanager.Simulation;
import com.mechalikh.pureedgesim.simulationmanager.SimulationAbstract;

/**
 * Runs the testbed on its test settings and reads what it wrote.
 * <p>
 * The runs use the settings of the testbed, shortened to four minutes, twenty
 * edge servers and forty devices, with one partition from 90 s to 180 s and no
 * random failure, and reshape them with {@code testbed.*} and {@code flocky.*}
 * overrides. The files are read by column name, so that a column added by the
 * testbed or a strategy breaks no test.
 */
public abstract class TestbedTestSupport extends AbstractTest {

	/** The settings of the testbed. */
	protected static final String TESTBED_SETTINGS = Testbed.SETTINGS;
	/** The test settings. */
	protected static final String TEST_SETTINGS = SETTINGS_FOLDER + "testbed/";
	/** Where the test runs write. */
	protected static final String TEST_OUTPUT = OUTPUT_FOLDER + "Testbed/";
	/** Number of devices of the test settings. */
	protected static final int DEVICES = 40;

	private final List<String> overrides = new ArrayList<>();
	/** The delivery mode in the names of the files, when it is not {@code tasks.delivery}. */
	protected static String labelDelivery;

	@AfterEach
	protected void clearOverrides() {
		for (String key : overrides)
			System.clearProperty(key);
		overrides.clear();
		labelDelivery = null;
		System.clearProperty(TestbedConfig.PREFIX + "config");
		System.clearProperty("flocky.config");
	}

	/** Overrides a key of {@code testbed.properties}. */
	protected void testbed(String key, String value) {
		set(TestbedConfig.PREFIX + key, value);
	}

	/** Overrides a key of {@code flocky.properties}. */
	protected void flocky(String key, String value) {
		set("flocky." + key, value);
	}

	private void set(String property, String value) {
		System.setProperty(property, value);
		overrides.add(property);
	}

	/**
	 * Runs the test scenario once, with one strategy.
	 *
	 * @param algorithm the name the strategy is registered under
	 * @return everything the run printed
	 */
	protected String run(String algorithm) throws Exception {
		Path output = Path.of(TEST_OUTPUT);
		Files.createDirectories(output);
		for (String kind : List.of("timeline", "partitions", "deployments", "device_types", "failures"))
			Files.deleteIfExists(output.resolve(label(algorithm) + "_" + kind + ".csv"));
		String parameters = Files.readString(Path.of(TEST_SETTINGS + "simulation_parameters.properties"))
				.replaceAll("(?m)^orchestration_algorithms=.*$", "orchestration_algorithms=" + algorithm);
		Path parametersFile = output.resolve("simulation_parameters_" + algorithm + ".properties");
		Files.writeString(parametersFile, parameters);
		System.setProperty(TestbedConfig.PREFIX + "config", TEST_SETTINGS + "testbed.properties");
		System.setProperty("flocky.config", TEST_SETTINGS + "flocky.properties");

		PrintStream originalOut = System.out;
		ByteArrayOutputStream console = new ByteArrayOutputStream();
		try {
			Simulation simulation = new Simulation();
			Testbed.configure(simulation);
			simulation.setCustomSettingsFolder(TESTBED_SETTINGS);
			simulation.setCustomFilePath(parametersFile.toString(), SimulationAbstract.Files.SIMULATION_PARAMETERS);
			simulation.setCustomOutputFolder(TEST_OUTPUT);
			System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
			simulation.launchSimulation();
		} finally {
			System.setOut(originalOut);
		}
		return console.toString(StandardCharsets.UTF_8);
	}

	/**
	 * @return the beginning of the names of the files of a run, which ends with its
	 *         delivery mode
	 */
	protected static String label(String algorithm) {
		return "testbed_" + algorithm + "_" + DEVICES + "_devices_" + (labelDelivery != null ? labelDelivery
				: System.getProperty(TestbedConfig.PREFIX + "tasks.delivery", "result"));
	}

	/**
	 * Reads one of the CSV files of a run.
	 *
	 * @param algorithm the strategy of the run
	 * @param kind      the file, for example {@code timeline}
	 * @return its rows, each a map from column name to value
	 */
	protected static List<Map<String, String>> rows(String algorithm, String kind) throws Exception {
		return rows(Path.of(TEST_OUTPUT, label(algorithm) + "_" + kind + ".csv"));
	}

	/**
	 * @param file a CSV file with a header
	 * @return its rows, each a map from column name to value
	 */
	protected static List<Map<String, String>> rows(Path file) throws Exception {
		List<String> lines = Files.readAllLines(file);
		String[] header = lines.get(0).split(",", -1);
		List<Map<String, String>> rows = new ArrayList<>();
		for (String line : lines.subList(1, lines.size())) {
			String[] cells = line.split(",", -1);
			Map<String, String> row = new LinkedHashMap<>();
			for (int i = 0; i < header.length; i++)
				row.put(header[i], i < cells.length ? cells[i] : "");
			rows.add(row);
		}
		return rows;
	}

	/** @return the row of the timeline taken at the given time */
	protected static Map<String, String> at(List<Map<String, String>> timeline, double time) {
		for (Map<String, String> row : timeline)
			if (Double.parseDouble(row.get("time_s")) == time)
				return row;
		throw new AssertionError("no timeline row at " + time + " s");
	}

	/** @return the value of a column, as a number */
	protected static double number(Map<String, String> row, String column) {
		return Double.parseDouble(row.get(column));
	}

	/** Reads the count the report printed just before the given words. */
	protected static int reported(String console, String words) {
		Matcher matcher = Pattern.compile("(\\d+) " + words).matcher(console);
		if (!matcher.find())
			throw new AssertionError("the report does not say how many " + words);
		return Integer.parseInt(matcher.group(1));
	}

	/** Reads the overall success rate the report printed. */
	protected static double successRate(String console) {
		Matcher matcher = Pattern.compile("failed \\(([0-9.,]+)% succeeded;").matcher(console);
		if (!matcher.find())
			throw new AssertionError("no testbed report in the output");
		return Double.parseDouble(matcher.group(1).replace(',', '.'));
	}
}
