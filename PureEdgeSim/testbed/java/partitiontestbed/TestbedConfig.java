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
	/**
	 * Whether a node can change partition group while a partition lasts: a moving
	 * server out of contact with its own group joins a group it is in range of, and
	 * a device attaches to an access server of another group, each time the strategy
	 * admits it ({@link OrchestrationStrategy#admitJoin}). {@code false} keeps every node
	 * in the group the partition gave it.
	 */
	public final boolean groupTransfer;
	/**
	 * Range of the radio between two devices, in metres; 0 when devices have none.
	 * The link exists while they are in range; whether a strategy uses it is its
	 * {@link NetworkPolicy}.
	 */
	public final double d2dRange;
	/** Latency of a radio link between two devices: a fixed part, in seconds. */
	public final double d2dBaseLatency;
	/** And a part per kilometre of distance, in seconds. */
	public final double d2dLatencyPerKm;
	/**
	 * Whether a radio link (between two devices, between a device and an access
	 * server, or to a moving server) still carries between two nodes that a
	 * partition puts in two groups. {@code false}: a partition isolates its groups
	 * completely, and a node crosses only by joining another group. {@code true}:
	 * the partition cuts the wired links between its groups, and a radio contact
	 * between them is a link like any other, which a strategy whose policy
	 * {@linkplain NetworkPolicy#bridgeGroups bridges groups} routes over.
	 */
	public final boolean crossGroupRadio;
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
	private final Map<Integer, Boolean> serviceLossless = new HashMap<>();
	private final Map<Integer, Double> serviceDeliveryWindow = new HashMap<>();
	private final Map<Integer, Double> serviceTarget = new HashMap<>();
	private final Map<Integer, Double> serviceMemory = new HashMap<>();
	private final Map<Integer, Double> serviceStartup = new HashMap<>();

	// Requirements and instances
	/**
	 * Whether a task must above all reach its device, late or not
	 * ({@code tasks.requirement=lossless}), rather than reach it before its
	 * deadline ({@code deadline}).
	 */
	public final boolean lossless;
	/** Time after its creation within which a task still counts as delivered, in seconds. */
	public final double deliveryWindow;
	/** Memory an instance of a service holds on its host, in megabytes. */
	public final double instanceMemory;
	/** Time an instance takes to start, in seconds. */
	public final double instanceStartup;
	/**
	 * Whether a failed server loses its running tasks and its instances
	 * ({@code failures.node_failure_mode=crash}), rather than only being cut off
	 * with its state kept ({@code isolate}).
	 */
	public final boolean nodeCrash;

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
		groupTransfer = bool("partitions.group_transfer", true);
		crossGroupRadio = bool("partitions.cross_group_radio", false);
		d2dRange = number("d2d.range_m", 0, Double.MAX_VALUE, 0);
		d2dBaseLatency = number("d2d.base_latency_ms", 0, Double.MAX_VALUE, 2) / 1000;
		d2dLatencyPerKm = number("d2d.latency_per_km_ms", 0, Double.MAX_VALUE, 4) / 1000;
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
			} else if ("requirement".equals(setting)) {
				String requirement = choice(key, null, "deadline", "lossless");
				if (requirement != null)
					serviceLossless.put(index, "lossless".equals(requirement));
			} else if ("delivery_window_s".equals(setting))
				serviceDeliveryWindow.put(index, number(key, 0, Double.MAX_VALUE));
			else if ("target_pct".equals(setting))
				serviceTarget.put(index, number(key, 0, 100));
			else if ("memory_mb".equals(setting))
				serviceMemory.put(index, number(key, 0, Double.MAX_VALUE));
			else if ("startup_ms".equals(setting))
				serviceStartup.put(index, number(key, 0, Double.MAX_VALUE) / 1000);
			else
				throw invalid(key, "expected services.<n>.<traits|delivery|pipeline|requirement|delivery_window_s|"
						+ "target_pct|memory_mb|startup_ms>");
		}

		String delivery = required("tasks.delivery").trim().toLowerCase();
		if (!"result".equals(delivery) && !"fire_and_forget".equals(delivery))
			throw invalid("tasks.delivery", "expected result or fire_and_forget");
		returnResults = "result".equals(delivery);
		lossless = "lossless".equals(choice("tasks.requirement", "deadline", "deadline", "lossless"));
		deliveryWindow = number("tasks.delivery_window_s", 0, Double.MAX_VALUE, 60);
		instanceMemory = number("instances.memory_mb", 0, Double.MAX_VALUE, 512);
		instanceStartup = number("instances.startup_ms", 0, Double.MAX_VALUE, 500) / 1000;
		nodeCrash = "crash".equals(choice("failures.node_failure_mode", "isolate", "isolate", "crash"));

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

	/**
	 * @param service the index of a service
	 * @return whether its requirement is to reach the device at all
	 *         ({@code lossless}) rather than before the deadline
	 */
	public boolean lossless(int service) {
		return serviceLossless.getOrDefault(service, lossless);
	}

	/**
	 * @param service the index of a service
	 * @return the time after its creation within which a task of it still counts
	 *         as delivered, in seconds
	 */
	public double deliveryWindow(int service) {
		return serviceDeliveryWindow.getOrDefault(service, deliveryWindow);
	}

	/**
	 * @param service the index of a service
	 * @return the share of its tasks that must meet the requirement, in percent,
	 *         or NaN when it sets no target
	 */
	public double target(int service) {
		return serviceTarget.getOrDefault(service, Double.NaN);
	}

	/**
	 * @param service the index of a service
	 * @return the memory an instance of it holds, in megabytes
	 */
	public double instanceMemory(int service) {
		return serviceMemory.getOrDefault(service, instanceMemory);
	}

	/**
	 * @param service the index of a service
	 * @return the time an instance of it takes to start, in seconds
	 */
	public double instanceStartup(int service) {
		return serviceStartup.getOrDefault(service, instanceStartup);
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
