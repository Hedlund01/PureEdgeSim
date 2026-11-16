package partitiontestbed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode.LinkOrientation;
import com.mechalikh.pureedgesim.locationmanager.MobilityModelFactory;
import com.mechalikh.pureedgesim.network.NetworkLink;
import com.mechalikh.pureedgesim.simulationengine.PureEdgeSim;

/**
 * Everything the testbed shares between the framework classes of one run, and
 * everything a strategy may see.
 * <p>
 * The framework builds the simulation manager first, then the network model, the
 * nodes, the topology and the orchestrator, each from the manager alone; the
 * manager therefore owns one world. Nothing in it is static, so the iterations of
 * a sweep do not leak into each other.
 * <p>
 * The testbed draws its random numbers from its own sources, seeded by
 * {@code seed} in {@code testbed.properties}: one for the continuum (positions,
 * roles, traits, services, devices) and one, in the {@link FailureInjector}, for
 * the failures and the partition groups. A strategy must use a source of its own,
 * so that every strategy of a sweep faces the same continuum, the same failures
 * and the same partitions. Every source is seeded by {@link #seed()}, which
 * differs from one replication of the sweep to the next and from nothing else.
 * <p>
 * <b>Attachment.</b> A device reaches the network through one access server at a
 * time, over a radio link: the one it was placed next to, until that server
 * fails or the device or the server moves out of {@code devices.radio_range_m}.
 * The device then attaches to the nearest access server in range that answers,
 * and its service is deployed again from there; with none in range it is cut
 * off until one comes back.
 *
 * @see TestbedSimulationManager
 */
public class TestbedWorld {

	/** Where a device's service runs. */
	public static final class Placement {
		/** The device that calls it. */
		public final ComputingNode device;
		/** The access server of the device. */
		public final EdgeServer home;
		/** The server that runs it, or {@code null} for the cloud. */
		public final EdgeServer host;
		/** The service. */
		public final Service service;
		/** What the strategy keeps about it, from {@link Deployment#detail}. */
		public final Object detail;

		Placement(ComputingNode device, EdgeServer home, EdgeServer host, Service service, Object detail) {
			this.device = device;
			this.home = home;
			this.host = host;
			this.service = service;
			this.detail = detail;
		}

		/**
		 * @param world the testbed
		 * @return the computing node that runs the service
		 */
		public ComputingNode node(TestbedWorld world) {
			return host == null ? world.cloud : host.node;
		}
	}

	/** A kind of end device of {@code edge_devices.xml}, and the application it runs. */
	public static final class DeviceType {
		/** Name of the type, for example {@code Security camera}. */
		public final String name;
		/** Index of its application in {@code applications.xml}, which is its service. */
		public final int application;

		DeviceType(String name, int application) {
			this.name = name;
			this.application = application;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	/** One partition in progress: the group of every server. */
	public static final class ActivePartition {
		/** The partition of the file. */
		public final TestbedConfig.PartitionSpec spec;
		/** The group of each server. */
		public final Map<EdgeServer, Integer> groupOf;
		/** What each group holds, and what happened in it. */
		public final List<TestbedMetrics.GroupRecord> groups;

		ActivePartition(TestbedConfig.PartitionSpec spec, Map<EdgeServer, Integer> groupOf,
				List<TestbedMetrics.GroupRecord> groups) {
			this.spec = spec;
			this.groupOf = groupOf;
			this.groups = groups;
		}
	}

	final TestbedSimulationManager manager;
	/** The configuration of the testbed. */
	public final TestbedConfig config;
	/** The random source of the continuum. */
	public final Random random;
	/** What is measured. */
	public final TestbedMetrics metrics;
	/** The strategy under test. */
	final PlacementStrategy strategy;

	/** Which run of the sweep this is, from 0. */
	public final int replication;
	/** Breaks the network, and is told of the mesh links that appear during the run. */
	FailureInjector injector;
	/** Creates the links that appear during the run. */
	TestbedTopologyCreator creator;
	/** The two radio links, up and down, between each device and each access server it has used. */
	private final Map<ComputingNode, Map<EdgeServer, NetworkLink[]>> radio = new HashMap<>();

	ComputingNode cloud = ComputingNode.NULL;
	LiveTopology topology;
	/** Pairs of edge servers joined by a mesh link, each pair once. */
	final List<ComputingNode[]> meshPairs = new ArrayList<>();
	private List<Service> services = List.of();

	private final List<EdgeServer> servers = new ArrayList<>();
	private final Map<ComputingNode, EdgeServer> serverOf = new HashMap<>();
	private final Map<ComputingNode, EdgeServer> homeOf = new LinkedHashMap<>();
	private final Map<ComputingNode, DeviceType> deviceTypeOf = new HashMap<>();
	private final List<DeviceType> deviceTypes = new ArrayList<>();
	private final Map<ComputingNode, Placement> placements = new LinkedHashMap<>();
	private final List<ActivePartition> active = new ArrayList<>();

	TestbedWorld(TestbedSimulationManager manager, TestbedConfig config, PlacementStrategy strategy) {
		this.manager = manager;
		this.config = config;
		this.strategy = strategy;
		this.replication = Testbed.replication();
		this.random = new Random(seed());
		this.metrics = new TestbedMetrics(this);
	}

	/**
	 * @return the seed of this run: {@code seed} of {@code testbed.properties},
	 *         moved by the replication
	 */
	public long seed() {
		return config.seed + 7919L * replication;
	}

	/** @return the current simulation time, in seconds */
	public double now() {
		return manager.getSimulation().clock();
	}

	/** @return the simulation engine, for a strategy that creates its own entities */
	public PureEdgeSim simulation() {
		return manager.getSimulation();
	}

	/** @return the strategy under test */
	public PlacementStrategy strategy() {
		return strategy;
	}

	// -------------------------------------------------------------- servers

	/**
	 * Gives every edge server its traits and its roles, and draws the services.
	 *
	 * @param edgeNodes the edge data centers, already named and placed
	 */
	void createServers(List<ComputingNode> edgeNodes) {
		services = Collections.unmodifiableList(Service.fromApplications(config, random));
		Set<String> access = pick(edgeNodes, config.accessNodes, config.accessNodeFraction, "access_nodes");
		Set<String> gateways = pick(edgeNodes, config.gatewayNodes, config.gatewayFraction, "gateway_nodes");
		Set<String> mobile = Set.of();
		if (config.serversMove()) {
			int count = (int) Math.round(config.mobileServerFraction * edgeNodes.size());
			mobile = config.mobileServers.isEmpty() && count == 0 ? Set.of()
					: pick(edgeNodes, config.mobileServers, config.mobileServerFraction, "servers.mobile_nodes");
		}
		for (ComputingNode node : edgeNodes) {
			Set<Trait> traits = EnumSet.noneOf(Trait.class);
			for (Trait trait : Trait.values())
				if (random.nextDouble() < config.traitProbability.get(trait))
					traits.add(trait);
			EdgeServer server = new EdgeServer(node, traits, access.contains(node.getName()),
					gateways.contains(node.getName()), mobile.contains(node.getName()));
			node.setPeriphery(server.access);
			if (server.mobile)
				node.setMobilityModel(MobilityModelFactory
						.create(config.serverMobilityModel, manager, node.getMobilityModel().getCurrentLocation())
						.setMobile(true).setSpeed(config.serverSpeed).setMinPauseDuration(config.serverMinPause)
						.setMaxPauseDuration(config.serverMaxPause).setMinMobilityDuration(config.serverMinMove)
						.setMaxMobilityDuration(config.serverMaxMove));
			servers.add(server);
			serverOf.put(node, server);
		}
	}

	/**
	 * Chooses the servers of a role, by name when the file names them, at random
	 * otherwise, and always at least one.
	 */
	private Set<String> pick(List<ComputingNode> nodes, List<String> named, double fraction, String key) {
		Set<String> names = new HashSet<>();
		Set<String> existing = new HashSet<>();
		for (ComputingNode node : nodes)
			existing.add(node.getName());
		if (!named.isEmpty()) {
			for (String name : named) {
				if (!existing.contains(name))
					throw new IllegalArgumentException("TestbedWorld - \"" + key + "\" names \"" + name
							+ "\", which is not an edge server (dc1 to dc" + nodes.size() + ")");
				names.add(name);
			}
			return names;
		}
		List<ComputingNode> shuffled = new ArrayList<>(nodes);
		Collections.shuffle(shuffled, random);
		int count = Math.max(1, (int) Math.round(fraction * nodes.size()));
		for (int i = 0; i < count; i++)
			names.add(shuffled.get(i).getName());
		return names;
	}

	/** @return the edge servers, {@code dc1} to {@code dcN} */
	public List<EdgeServer> servers() {
		return Collections.unmodifiableList(servers);
	}

	/** @return the access servers, which devices attach to */
	public List<EdgeServer> accessServers() {
		List<EdgeServer> list = new ArrayList<>();
		for (EdgeServer server : servers)
			if (server.access)
				list.add(server);
		return list;
	}

	/**
	 * @param node an edge data center
	 * @return its server, or {@code null} for any other node
	 */
	public EdgeServer serverOf(ComputingNode node) {
		return serverOf.get(node);
	}

	/** @return the services, in the order of {@code applications.xml} */
	public List<Service> services() {
		return services;
	}

	/** @return the cloud data center */
	public ComputingNode cloud() {
		return cloud;
	}

	/** @return the live topology */
	public LiveTopology topology() {
		return topology;
	}

	// -------------------------------------------------------------- devices

	/** Attaches a device to the access server it was placed next to. */
	void attach(ComputingNode device, EdgeServer home) {
		homeOf.put(device, home);
	}

	/**
	 * @param device an end device
	 * @param server an access server
	 * @return the radio links between them, up then down, created the first time
	 *         they are asked for and out of use until the device attaches
	 */
	NetworkLink[] radioLinks(ComputingNode device, EdgeServer server) {
		return radio.computeIfAbsent(device, key -> new HashMap<>()).computeIfAbsent(server,
				key -> creator.radio(device, server.node));
	}

	/**
	 * @param device an end device
	 * @return whether it can reach its access server now: in range, and answering
	 */
	public boolean attached(ComputingNode device) {
		for (NetworkLink link : radioLinks(device, homeOf.get(device)))
			if (!topology.carries(link))
				return false;
		return true;
	}

	/** @return whether a device or an edge server moves during the run */
	public boolean mobile() {
		for (EdgeServer server : servers)
			if (server.mobile)
				return true;
		for (ComputingNode device : homeOf.keySet())
			if (device.getMobilityModel().isMobile())
				return true;
		return false;
	}

	/**
	 * Lets every device that lost its access server look for another: called
	 * whenever the network changes or something moved.
	 * <p>
	 * A device stays with its access server for as long as that server is in range
	 * and answers. Otherwise it attaches to the nearest access server in range that
	 * does, and waits, cut off, when there is none. A device in range of a server
	 * that failed only looks elsewhere when {@code devices.failover} is on.
	 */
	void updateAttachments() {
		for (Map.Entry<ComputingNode, EdgeServer> attachment : new ArrayList<>(homeOf.entrySet())) {
			ComputingNode device = attachment.getKey();
			EdgeServer home = attachment.getValue();
			boolean inRange = device.getMobilityModel().distanceTo(home.node) <= config.radioRange;
			for (NetworkLink link : radioLinks(device, home))
				topology.enable(link, inRange, now());
			if (inRange && (!topology.isFailed(home.node) || !config.failover))
				continue;
			EdgeServer nearest = null;
			double best = config.radioRange;
			for (EdgeServer server : servers) {
				if (!server.access || server == home || topology.isFailed(server.node))
					continue;
				double distance = device.getMobilityModel().distanceTo(server.node);
				if (distance <= best) {
					best = distance;
					nearest = server;
				}
			}
			if (nearest != null)
				handover(device, home, nearest);
		}
	}

	private void handover(ComputingNode device, EdgeServer from, EdgeServer to) {
		for (NetworkLink link : radioLinks(device, from))
			topology.enable(link, false, now());
		NetworkLink[] links = radioLinks(device, to);
		for (NetworkLink link : links)
			topology.enable(link, true, now());
		homeOf.put(device, to);
		device.setOrchestrator(to.node);
		device.setCurrentLink(links[0], LinkOrientation.UP_LINK);
		device.setCurrentLink(links[1], LinkOrientation.DOWN_LINK);
		// The service was deployed from the server the device left: its next task
		// deploys it again from the new one.
		unplace(device);
		metrics.handover(device, from, to);
		strategy.deviceMoved(device, from, to);
	}

	/**
	 * @param device an end device
	 * @return the access server it is attached to
	 */
	public EdgeServer homeOf(ComputingNode device) {
		return homeOf.get(device);
	}

	/** @return every device with the server it is attached to */
	public Map<ComputingNode, EdgeServer> homes() {
		return Collections.unmodifiableMap(homeOf);
	}

	/** Records the type of a device. Devices of the same type share one record. */
	void setDeviceType(ComputingNode device, String name, int application) {
		DeviceType type = null;
		for (DeviceType known : deviceTypes)
			if (known.name.equals(name))
				type = known;
		if (type == null) {
			type = new DeviceType(name, application);
			deviceTypes.add(type);
		}
		deviceTypeOf.put(device, type);
	}

	/**
	 * @param device an end device
	 * @return its type
	 */
	public DeviceType deviceTypeOf(ComputingNode device) {
		return deviceTypeOf.get(device);
	}

	/** @return the device types, in the order of {@code edge_devices.xml} */
	public List<DeviceType> deviceTypes() {
		return Collections.unmodifiableList(deviceTypes);
	}

	// ----------------------------------------------------------- placements

	/** Records where a device's service now runs. */
	void place(Placement placement) {
		placements.put(placement.device, placement);
	}

	/** Records the placement a successful deployment made. */
	void place(Deployment deployment) {
		place(new Placement(deployment.device, deployment.home, deployment.host, deployment.service,
				deployment.detail));
	}

	/**
	 * @param device an end device
	 * @return where its service runs, or {@code null} when it is not deployed
	 */
	public Placement placementOf(ComputingNode device) {
		return placements.get(device);
	}

	/** @return every placement in force */
	public Collection<Placement> placements() {
		return Collections.unmodifiableCollection(new ArrayList<>(placements.values()));
	}

	/**
	 * Ends the placement of a device, if it has one, and lets the strategy free
	 * what it held.
	 *
	 * @param device the device whose service is moved away
	 */
	void release(ComputingNode device) {
		Placement placement = placements.remove(device);
		if (placement != null)
			strategy.release(placement);
	}

	/**
	 * Moves the service of a device away, so that its next task deploys it again:
	 * what a strategy calls when it decides a placement is no longer good.
	 *
	 * @param device the device
	 */
	public void unplace(ComputingNode device) {
		device.setApplicationPlaced(false);
		release(device);
	}

	// ----------------------------------------------------------- partitions

	void partitionStarted(ActivePartition partition) {
		active.add(partition);
	}

	void partitionEnded(ActivePartition partition) {
		active.remove(partition);
	}

	/**
	 * @param a an edge server
	 * @param b another edge server
	 * @return whether a partition in progress puts them in two groups
	 */
	public boolean separated(EdgeServer a, EdgeServer b) {
		for (ActivePartition partition : active)
			if (!partition.groupOf.get(a).equals(partition.groupOf.get(b)))
				return true;
		return false;
	}

	/** @return the partitions in progress */
	public List<ActivePartition> activePartitions() {
		return Collections.unmodifiableList(active);
	}
}
