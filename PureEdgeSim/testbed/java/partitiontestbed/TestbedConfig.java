package partitiontestbed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every knob of the testbed, read from the {@code testbed.*} keys of the common
 * file and from {@code testbed.properties} of the scenario: the edge layer,
 * the services, what devices expect of their tasks, the failures, the partitions
 * and the metrics. Nothing here belongs to a strategy: each strategy reads its own
 * file.
 * <p>
 * Any key can be overridden with {@code -Dtestbed.<key>=<value>}, and
 * {@code -Dtestbed.config=<file>} reads another file.
 *
 * @see Testbed
 */
public class TestbedConfig extends PropertiesFile {

	/** Prefix of the command line overrides of this file. */
	public static final String PREFIX = "testbed.";

	/** How the edge servers of a partition are split into groups. */
	public enum GroupMode {
		/** Vertical bands of the map holding the same number of servers. */
		GEO,
		/** Every server draws its group at random. */
		RANDOM,
		/** The groups are named server by server. */
		EXPLICIT
	}

	/**
	 * One scheduled partition. While it lasts, every link between two groups is
	 * down, and at most one group keeps the uplinks of its gateways.
	 */
	public static final class PartitionSpec {
		/** Index of the partition in the file, from one. */
		public final int index;
		/** Seconds into the run at which the edge layer splits. */
		public final double start;
		/** Seconds the split lasts. */
		public final double duration;
		/** How the groups are formed. */
		public final GroupMode mode;
		/** Number of groups, for {@link GroupMode#GEO} and {@link GroupMode#RANDOM}. */
		public final int groupCount;
		/** Server names of each group, for {@link GroupMode#EXPLICIT}. */
		public final List<List<String>> explicitGroups;
		/** The group that keeps the cloud: its index, {@code none} or {@code random}. */
		public final String cloud;

		PartitionSpec(int index, double start, double duration, GroupMode mode, int groupCount,
				List<List<String>> explicitGroups, String cloud) {
			this.index = index;
			this.start = start;
			this.duration = duration;
			this.mode = mode;
			this.groupCount = groupCount;
			this.explicitGroups = explicitGroups;
			this.cloud = cloud;
		}

		@Override
		public String toString() {
			String groups = mode == GroupMode.EXPLICIT ? String.valueOf(explicitGroups)
					: mode.name().toLowerCase() + ":" + groupCount;
			return String.format("partition %d [%.0f s, %.0f s) groups=%s cloud=%s", index, start, start + duration,
					groups, cloud);
		}
	}

	// Edge layer
	public final int edgeNodes;
	public final double accessNodeFraction;
	public final List<String> accessNodes;
	public final double gatewayFraction;
	public final List<String> gatewayNodes;
	public final double linkRange;
	public final int minDegree;
	public final double baseLatency;
	public final double latencyPerKm;
	public final double devicesRadius;
	/** Range of a device's radio, in metres: it attaches to an access server this close. */
	public final double radioRange;
	/** Whether a device whose access server stops answering attaches to another one in range. */
	public final boolean failover;
	public final long seed;
	/** Number of times the sweep is run, each on its own continuum, failures and tasks. */
	public final int replications;

	// Edge servers that move
	public final double mobileServerFraction;
	public final List<String> mobileServers;
	public final String serverMobilityModel;
	public final double serverSpeed;
	public final double serverMinPause;
	public final double serverMaxPause;
	public final double serverMinMove;
	public final double serverMaxMove;

	// Traits and services
	public final Map<Trait, Double> traitProbability = new EnumMap<>(Trait.class);
	public final Set<Trait> cloudTraits;
	public final double serviceTraitProbability;
	private final Map<Integer, Set<Trait>> serviceTraits = new HashMap<>();
	private final Map<Integer, Boolean> serviceReturnsResults = new HashMap<>();
	private final Map<Integer, Boolean> serviceEdgePipeline = new HashMap<>();

	// Tasks
	/**
	 * Whether a task must bring its result back to the device that sent it
	 * ({@code tasks.delivery=result}), or is done once it has run on its host
	 * ({@code fire_and_forget}).
	 */
	public final boolean returnResults;

	// Random failures, per second
	public final double linkFailureRate;
	public final double linkRepairTime;
	public final double wanFailureRate;
	public final double wanRepairTime;
	public final double nodeFailureRate;
	public final double nodeRepairTime;

	// Partitions
	public final List<PartitionSpec> partitions;

	// Metrics and visualisation
	public final double sampleInterval;
	public final long framePause;

	/**
	 * Reads and validates the testbed file.
	 *
	 * @param defaultFile the file to read unless {@code -Dtestbed.config} names
	 *                    another
	 */
	public TestbedConfig(String defaultFile) {
		super(PREFIX, defaultFile);
		edgeNodes = integer("edge_nodes", 2, 10_000);
		accessNodeFraction = number("access_node_fraction", 0, 1);
		accessNodes = names("access_nodes");
		gatewayFraction = number("gateway_fraction", 0, 1);
		gatewayNodes = names("gateway_nodes");
		linkRange = number("mesh.link_range_m", 0, Double.MAX_VALUE);
		minDegree = integer("mesh.min_degree", 0, edgeNodes - 1);
		baseLatency = millis("mesh.base_latency_ms");
		latencyPerKm = millis("mesh.latency_per_km_ms");
		devicesRadius = number("devices.radius_m", 0, Double.MAX_VALUE);
		radioRange = number("devices.radio_range_m", 0, Double.MAX_VALUE, 300);
		failover = bool("devices.failover", true);
		seed = (long) number("seed", Long.MIN_VALUE, Long.MAX_VALUE);
		replications = integer("replications", 1, 10_000, 1);

		mobileServerFraction = number("servers.mobile_fraction", 0, 1, 0);
		mobileServers = names("servers.mobile_nodes");
		serverMobilityModel = properties.getProperty("servers.mobility_model", "random-waypoint").trim();
		serverSpeed = number("servers.speed_mps", 0, Double.MAX_VALUE, 10);
		serverMinPause = number("servers.min_pause_s", 0, Double.MAX_VALUE, 0);
		serverMaxPause = number("servers.max_pause_s", serverMinPause, Double.MAX_VALUE, serverMinPause);
		serverMinMove = number("servers.min_move_s", 0, Double.MAX_VALUE, 60);
		serverMaxMove = number("servers.max_move_s", serverMinMove, Double.MAX_VALUE, Math.max(serverMinMove, 300));

		for (Trait trait : Trait.values())
			traitProbability.put(trait, number("traits." + trait.name(), 0, 1));
		cloudTraits = traits(properties.getProperty("cloud.traits", ""), "cloud.traits");
		serviceTraitProbability = number("services.trait_probability", 0, 1);
		for (String key : properties.stringPropertyNames()) {
			if (!key.startsWith("services.") || key.equals("services.trait_probability"))
				continue;
			int dot = key.lastIndexOf('.');
			String setting = key.substring(dot + 1);
			int index;
			try {
				index = Integer.parseInt(key.substring("services.".length(), Math.max(dot, "services.".length())));
			} catch (NumberFormatException e) {
				throw invalid(key, "the service must be named by its index in applications.xml, from 0");
			}
			if ("traits".equals(setting))
				serviceTraits.put(index, traits(properties.getProperty(key), key));
			else if ("delivery".equals(setting)) {
				String delivery = choice(key, null, "result", "fire_and_forget");
				if (delivery != null)
					serviceReturnsResults.put(index, "result".equals(delivery));
			} else if ("pipeline".equals(setting)) {
				String pipeline = choice(key, null, "device", "edge");
				if (pipeline != null)
					serviceEdgePipeline.put(index, "edge".equals(pipeline));
			} else
				throw invalid(key, "expected services.<n>.<traits|delivery|pipeline>");
		}

		String delivery = required("tasks.delivery").trim().toLowerCase();
		if (!"result".equals(delivery) && !"fire_and_forget".equals(delivery))
			throw invalid("tasks.delivery", "expected result or fire_and_forget");
		returnResults = "result".equals(delivery);

		linkFailureRate = number("failures.link_failure_rate_per_hour", 0, Double.MAX_VALUE) / 3600;
		linkRepairTime = number("failures.link_mean_repair_s", 0.001, Double.MAX_VALUE);
		wanFailureRate = number("failures.wan_failure_rate_per_hour", 0, Double.MAX_VALUE) / 3600;
		wanRepairTime = number("failures.wan_mean_repair_s", 0.001, Double.MAX_VALUE);
		nodeFailureRate = number("failures.node_failure_rate_per_hour", 0, Double.MAX_VALUE) / 3600;
		nodeRepairTime = number("failures.node_mean_repair_s", 0.001, Double.MAX_VALUE);

		partitions = Collections.unmodifiableList(readPartitions());

		sampleInterval = number("metrics.sample_interval_s", 0.01, Double.MAX_VALUE);
		framePause = integer("visualizer.frame_pause_ms", 0, Integer.MAX_VALUE);
	}

	/**
	 * @param service the index of a service, which is the index of its
	 *                application in {@code applications.xml}
	 * @return the traits the file requires for it, or {@code null} when the file
	 *         leaves them to chance
	 */
	public Set<Trait> serviceTraits(int service) {
		Set<Trait> traits = serviceTraits.get(service);
		return traits == null ? null : EnumSet.copyOf(traits);
	}

	/**
	 * @param service the index of a service
	 * @return whether its tasks bring a result back to the device:
	 *         {@code services.<n>.delivery}, or {@code tasks.delivery} when the
	 *         service does not say
	 */
	public boolean returnsResults(int service) {
		return serviceReturnsResults.getOrDefault(service, returnResults);
	}

	/**
	 * @param service the index of a service
	 * @return whether the stages of its subtask graph hand their output to the next
	 *         stage on the edge ({@code services.<n>.pipeline=edge}) instead of
	 *         through the device
	 */
	public boolean edgePipeline(int service) {
		return serviceEdgePipeline.getOrDefault(service, false);
	}

	/** @return whether the services do not all share one delivery mode */
	public boolean mixedDelivery() {
		return serviceReturnsResults.containsValue(!returnResults);
	}

	/** @return whether some edge servers move */
	public boolean serversMove() {
		return mobileServerFraction > 0 || !mobileServers.isEmpty();
	}

	private List<PartitionSpec> readPartitions() {
		Set<Integer> indices = new TreeSet<>();
		for (String key : properties.stringPropertyNames())
			if (key.startsWith("partition.")) {
				String[] parts = key.split("\\.");
				if (parts.length != 3)
					throw invalid(key, "expected partition.<n>.<start_s|duration_s|groups|cloud|enabled>");
				try {
					indices.add(Integer.parseInt(parts[1]));
				} catch (NumberFormatException e) {
					throw invalid(key, "the partition must be numbered");
				}
			}
		List<PartitionSpec> list = new ArrayList<>();
		for (int index : indices) {
			String prefix = "partition." + index + ".";
			if (!bool(prefix + "enabled", true))
				continue;
			double start = number(prefix + "start_s", 0, Double.MAX_VALUE);
			double duration = number(prefix + "duration_s", 0, Double.MAX_VALUE);
			String groups = required(prefix + "groups").trim();
			String cloud = properties.getProperty(prefix + "cloud", "none").trim().toLowerCase();
			validateCloud(prefix + "cloud", cloud);
			int colon = groups.indexOf(':');
			String mode = colon < 0 ? "" : groups.substring(0, colon).trim().toLowerCase();
			if ("geo".equals(mode) || "random".equals(mode)) {
				int count;
				try {
					count = Integer.parseInt(groups.substring(colon + 1).trim());
				} catch (NumberFormatException e) {
					throw invalid(prefix + "groups", "expected geo:<k> or random:<k>");
				}
				if (count < 1 || count > edgeNodes)
					throw invalid(prefix + "groups", "the number of groups must be between 1 and edge_nodes");
				list.add(new PartitionSpec(index, start, duration,
						"geo".equals(mode) ? GroupMode.GEO : GroupMode.RANDOM, count, List.of(), cloud));
			} else {
				List<List<String>> explicit = new ArrayList<>();
				for (String group : groups.split("\\|")) {
					List<String> members = new ArrayList<>();
					for (String name : group.split(","))
						if (!name.isBlank())
							members.add(name.trim());
					if (members.isEmpty())
						throw invalid(prefix + "groups", "a group of an explicit partition cannot be empty");
					explicit.add(members);
				}
				list.add(new PartitionSpec(index, start, duration, GroupMode.EXPLICIT, explicit.size(), explicit,
						cloud));
			}
		}
		return list;
	}

	private void validateCloud(String key, String cloud) {
		if (List.of("none", "random").contains(cloud))
			return;
		try {
			if (Integer.parseInt(cloud) < 0)
				throw invalid(key, "group indices start at 0");
		} catch (NumberFormatException e) {
			throw invalid(key, "expected none, random, or the index of the one group that keeps the cloud");
		}
	}
}
