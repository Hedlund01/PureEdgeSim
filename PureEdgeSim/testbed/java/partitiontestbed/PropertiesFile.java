package partitiontestbed;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * A validated properties file whose keys can be overridden from the command line.
 * <p>
 * A key is read from three places, each replacing the one before:
 * <ol>
 * <li>the <b>common file</b>, {@code scenarios/common.properties} or the file
 * {@code -Dtestbed.common} names, which holds what every scenario and every run
 * share. It names each key with the prefix of the file it belongs to,
 * {@code <prefix><key>};</li>
 * <li>the <b>file of the scenario</b>, which holds what the scenario sets
 * differently, under the plain {@code <key>}. A scenario that changes nothing
 * needs no file. {@code <prefix>config} names another file to read instead;</li>
 * <li>the <b>command line</b>: a JVM property named {@code <prefix><key>}, so a
 * sweep needs no copy of a file.</li>
 * </ol> The testbed reads
 * {@code testbed.properties} with the prefix {@code testbed.}, and every strategy
 * reads its own file with its own prefix, for example {@code flocky.}.
 * <p>
 * A value is checked when it is read, so that a typo fails the run before its
 * first event rather than half way through it.
 *
 * @see TestbedConfig
 */
public abstract class PropertiesFile {

	/** The keys and values, after the command line overrides. */
	protected final Properties properties = new Properties();
	private final String prefix;

	/** JVM properties that choose what is read, and are not keys of a file. */
	private static final Set<String> SELECTORS = Set.of("config", "common", "scenario", "settings", "output");

	/**
	 * Reads the common file, then the file named by {@code <prefix>config} or the
	 * given one, then the command line.
	 *
	 * @param prefix       the prefix of the keys of this file in the common file
	 *                     and on the command line, for example {@code testbed.}
	 * @param scenarioFile the file of the scenario, which need not exist
	 */
	protected PropertiesFile(String prefix, String scenarioFile) {
		this.prefix = prefix;
		properties.putAll(layers(prefix, scenarioFile));
	}

	/**
	 * Reads the keys of one file from the common file, the file of the scenario
	 * and the command line, in that order.
	 *
	 * @param prefix       the prefix of the keys in the common file and on the
	 *                     command line
	 * @param scenarioFile the file of the scenario, which need not exist
	 * @return the keys, without their prefix
	 */
	static Properties layers(String prefix, String scenarioFile) {
		Properties merged = new Properties();
		Properties common = load(commonFile(), System.getProperty(TestbedConfig.PREFIX + "common") != null);
		for (String key : common.stringPropertyNames())
			if (key.startsWith(prefix))
				merged.setProperty(key.substring(prefix.length()), common.getProperty(key));
		String named = System.getProperty(prefix + "config");
		merged.putAll(load(named != null ? named : scenarioFile, named != null));
		for (String key : System.getProperties().stringPropertyNames())
			if (key.startsWith(prefix) && !SELECTORS.contains(key.substring(prefix.length())))
				merged.setProperty(key.substring(prefix.length()), System.getProperty(key));
		return merged;
	}

	/** @return the common file: {@code -Dtestbed.common}, or the one of the scenarios folder */
	public static String commonFile() {
		return System.getProperty(TestbedConfig.PREFIX + "common", Testbed.COMMON);
	}

	private static Properties load(String file, boolean required) {
		Properties loaded = new Properties();
		if (!required && !new File(file).isFile())
			return loaded;
		try (InputStream input = new FileInputStream(file)) {
			loaded.load(input);
		} catch (IOException e) {
			throw new IllegalArgumentException("PropertiesFile - cannot read \"" + file + "\": " + e.getMessage(), e);
		}
		return loaded;
	}

	/** @return the prefix of the command line overrides of this file */
	public String prefix() {
		return prefix;
	}

	protected String required(String key) {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank())
			throw invalid(key, "the key is missing");
		return value;
	}

	protected double number(String key, double min, double max) {
		double value;
		try {
			value = Double.parseDouble(required(key).trim());
		} catch (NumberFormatException e) {
			throw invalid(key, "not a number");
		}
		if (value < min || value > max)
			throw invalid(key, "must be between " + min + " and " + max);
		return value;
	}

	/** A number that may be left out of the file. */
	protected double number(String key, double min, double max, double fallback) {
		String value = properties.getProperty(key);
		return value == null || value.isBlank() ? fallback : number(key, min, max);
	}

	/** A whole number that may be left out of the file. */
	protected int integer(String key, int min, int max, int fallback) {
		String value = properties.getProperty(key);
		return value == null || value.isBlank() ? fallback : integer(key, min, max);
	}

	/** One of a few words, in lower case, that may be left out of the file. */
	protected String choice(String key, String fallback, String... allowed) {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank())
			return fallback;
		value = value.trim().toLowerCase();
		if (!Arrays.asList(allowed).contains(value))
			throw invalid(key, "expected " + String.join(" or ", allowed));
		return value;
	}

	protected int integer(String key, int min, int max) {
		double value = number(key, min, max);
		if (value != Math.rint(value))
			throw invalid(key, "must be a whole number");
		return (int) value;
	}

	/** Reads a duration given in milliseconds and returns it in seconds. */
	protected double millis(String key) {
		return number(key, 0, Double.MAX_VALUE) / 1000;
	}

	protected boolean bool(String key) {
		String value = required(key).trim();
		if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
			throw invalid(key, "expected true or false");
		return Boolean.parseBoolean(value);
	}

	protected boolean bool(String key, boolean fallback) {
		return properties.getProperty(key) == null ? fallback : bool(key);
	}

	/** Reads a comma separated list of names, empty when the key is. */
	protected List<String> names(String key) {
		List<String> list = new ArrayList<>();
		for (String name : properties.getProperty(key, "").split(","))
			if (!name.isBlank())
				list.add(name.trim());
		return Collections.unmodifiableList(list);
	}

	/** Reads a comma separated list of traits, empty when the value is. */
	protected Set<Trait> traits(String value, String key) {
		Set<Trait> set = EnumSet.noneOf(Trait.class);
		for (String name : value.split(","))
			if (!name.isBlank())
				try {
					set.add(Trait.valueOf(name.trim().toUpperCase()));
				} catch (IllegalArgumentException e) {
					throw invalid(key, "unknown trait \"" + name.trim() + "\", expected one of "
							+ Arrays.toString(Trait.values()));
				}
		return set;
	}

	protected IllegalArgumentException invalid(String key, String message) {
		return new IllegalArgumentException(getClass().getSimpleName() + " - invalid \"" + key + "\" = \""
				+ properties.getProperty(key) + "\": " + message);
	}
}
