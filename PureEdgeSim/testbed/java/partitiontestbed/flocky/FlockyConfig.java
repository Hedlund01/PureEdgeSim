package partitiontestbed.flocky;

import partitiontestbed.PropertiesFile;

/**
 * The knobs of Flocky, read from the {@code flocky.*} keys of the common file,
 * and from {@code flocky.properties} of the scenario when it has one: its OAM catalog, its
 * discovery service, its metadata repository, Swirly and the deployment service.
 * The continuum, the failures and the partitions are the testbed's, in
 * {@code testbed.properties}.
 * <p>
 * Any key can be overridden with {@code -Dflocky.<key>=<value>}, and
 * {@code -Dflocky.config=<file>} reads another file.
 *
 * @see FlockyStrategy
 */
public class FlockyConfig extends PropertiesFile {

	/** Prefix of the command line overrides of this file. */
	public static final String PREFIX = "flocky.";

	// OAM catalog
	public final int implementationsPerService;
	public final double unikernelProbability;
	public final double minDefinitionMemory;
	public final double maxDefinitionMemory;
	public final int seedNodesPerDefinition;

	// Discovery
	public final double discoveryInterval;
	public final double maxDiscoveryDistance;
	public final double assumedDensity;
	public final int bootstrapPeers;
	public final double discoveryTimeout;
	public final double forgetAfter;

	// Repository
	public final double repositoryInterval;

	// Swirly and deployment
	public final boolean cloudFallback;
	public final boolean proactiveMigration;
	public final boolean allowLocal;
	public final double deploymentTimeout;
	public final double processingTime;
	public final double orphanTimeout;
	public final int maxClientsPerInstance;
	public final double memoryShare;

	// Message sizes, in bytes
	public final int pingBytes;
	public final int nodeEntryBytes;
	public final int summaryBaseBytes;
	public final int definitionIdBytes;
	public final int manifestBytes;

	/**
	 * Reads and validates the Flocky file.
	 *
	 * @param defaultFile the file to read unless {@code -Dflocky.config} names
	 *                    another
	 * @param edgeServers the number of edge servers of the testbed, which bounds the
	 *                    peers and the seed servers
	 */
	public FlockyConfig(String defaultFile, int edgeServers) {
		super(PREFIX, defaultFile);
		implementationsPerService = integer("oam.implementations_per_service", 1, 100);
		unikernelProbability = number("oam.unikernel_probability", 0, 1);
		minDefinitionMemory = number("oam.min_memory_mb", 0, Double.MAX_VALUE);
		maxDefinitionMemory = number("oam.max_memory_mb", minDefinitionMemory, Double.MAX_VALUE);
		// "all" installs every definition on every server, so any server can run any
		// service from the start, whichever group a partition puts it in.
		seedNodesPerDefinition = "all".equalsIgnoreCase(properties.getProperty("oam.seed_nodes_per_definition", "").trim())
				? edgeServers
				: integer("oam.seed_nodes_per_definition", 1, edgeServers);

		discoveryInterval = number("discovery.interval_s", 0.01, Double.MAX_VALUE);
		maxDiscoveryDistance = millis("discovery.max_distance_ms");
		assumedDensity = number("discovery.assumed_density", 0, Double.MAX_VALUE);
		bootstrapPeers = integer("discovery.bootstrap_peers", 1, edgeServers - 1);
		discoveryTimeout = millis("discovery.rpc_timeout_ms");
		forgetAfter = number("discovery.forget_after_s", 0, Double.MAX_VALUE);

		repositoryInterval = number("repository.interval_s", 0.01, Double.MAX_VALUE);

		cloudFallback = bool("swirly.cloud_fallback");
		proactiveMigration = bool("swirly.proactive_migration");
		allowLocal = bool("swirly.allow_local");
		deploymentTimeout = millis("swirly.rpc_timeout_ms");
		processingTime = millis("swirly.processing_ms");
		orphanTimeout = number("deployment.orphan_timeout_s", 0, Double.MAX_VALUE);
		maxClientsPerInstance = integer("deployment.max_clients_per_instance", 1, Integer.MAX_VALUE);
		memoryShare = number("deployment.memory_share", 0, 1);

		pingBytes = integer("messages.ping_bytes", 0, Integer.MAX_VALUE);
		nodeEntryBytes = integer("messages.node_entry_bytes", 0, Integer.MAX_VALUE);
		summaryBaseBytes = integer("messages.summary_base_bytes", 0, Integer.MAX_VALUE);
		definitionIdBytes = integer("messages.definition_id_bytes", 0, Integer.MAX_VALUE);
		manifestBytes = integer("messages.manifest_bytes", 0, Integer.MAX_VALUE);
	}
}
