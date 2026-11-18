package partitiontestbed.baselines;

import partitiontestbed.PropertiesFile;

/**
 * The knobs of the baseline strategies, read from the {@code baselines.*} keys of
 * the common file, and from {@code baselines.properties} of the scenario when it
 * has one. Any key can be overridden with {@code -Dbaselines.<key>=<value>}.
 */
public class BaselineConfig extends PropertiesFile {

	/** Prefix of the command line overrides of this file. */
	public static final String PREFIX = "baselines.";

	/** Time a sender of {@code RETRY} waits for an acknowledgement before it sends again, in seconds. */
	public final double retryInterval;
	/** Hosts {@code REPLICATED} runs every task on, the access server first. */
	public final int copies;

	/**
	 * @param defaultFile the file to read unless {@code -Dbaselines.config} names
	 *                    another
	 */
	public BaselineConfig(String defaultFile) {
		super(PREFIX, defaultFile);
		retryInterval = number("retry.interval_s", 0.01, Double.MAX_VALUE, 1);
		copies = integer("replicated.copies", 1, 100, 2);
	}
}
