package zip.hedlund.pureedgesim.edgesdk.examples;

import java.nio.file.Path;

/** Shared helpers of the examples. */
final class Examples {

	private Examples() {
	}

	/**
	 * @return where an example writes its results: {@code output/edgesdk/<name>},
	 *         or under the folder given by {@code -Dedgesdk.output=...}
	 */
	static Path output(String name) {
		return Path.of(System.getProperty("edgesdk.output", "output/edgesdk")).resolve(name);
	}

	/** @return a duration shortened by {@code -Dedgesdk.quick=true} (used by the example tests) */
	static double duration(double seconds) {
		return Boolean.getBoolean("edgesdk.quick") ? Math.min(seconds, 150) : seconds;
	}

	/** @return a replication count shortened by {@code -Dedgesdk.quick=true} */
	static int replications(int n) {
		return Boolean.getBoolean("edgesdk.quick") ? 1 : n;
	}
}
