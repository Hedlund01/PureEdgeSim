package partitiontestbed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode.LinkOrientation;
import com.mechalikh.pureedgesim.datacentersmanager.ComputingNodesGenerator;
import com.mechalikh.pureedgesim.datacentersmanager.DefaultTopologyCreator;
import com.mechalikh.pureedgesim.network.NetworkLink;
import com.mechalikh.pureedgesim.network.NetworkLink.NetworkLinkTypes;
import com.mechalikh.pureedgesim.network.NetworkLinkMan;
import com.mechalikh.pureedgesim.network.NetworkLinkWanDown;
import com.mechalikh.pureedgesim.network.NetworkLinkWanUp;
import com.mechalikh.pureedgesim.network.NetworkLinkWifiDeviceToDevice;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;

/**
 * Builds the network of the edge layer.
 * <p>
 * Two edge nodes are linked when they are within {@code mesh.link_range_m} of
 * each other, every node is then linked to its nearest nodes until it has
 * {@code mesh.min_degree} links, and the closest pair of nodes across two
 * disconnected parts is linked until the mesh is connected: whatever the
 * density, a healthy network has a path between any two nodes, and only the
 * failures and the partitions take it away. A link's latency grows with its
 * length, {@code mesh.base_latency_ms} plus {@code mesh.latency_per_km_ms} per
 * kilometre, since latency is what a decentralised orchestrator can measure of its neighbours.
 * <p>
 * Each gateway has its own uplink to the cloud. Each device is linked to the
 * access node it was placed next to, which is also its orchestrator: Swirly runs
 * on the node of the device that asks for a deployment. There is no other way
 * out for a device, so a device reaches the cloud, or another edge node, only
 * through the mesh of its access node.
 * <p>
 * The links that appear during the run are created here too: the radio links of
 * a device that attaches to another access server, and the mesh links of an edge
 * server that moves. A link to a moving server exists while the two servers are
 * within {@code mesh.link_range_m} and no partition puts them in two groups, and
 * its latency follows their distance; the links between two servers that do not
 * move stay as the mesh was built.
 *
 * @see TestbedNodesGenerator
 */
public class TestbedTopologyCreator extends DefaultTopologyCreator {

	private final TestbedWorld world;
	private final Set<Long> linked = new HashSet<>();
	/** The two links of each pair of the mesh, one per direction. */
	private final Map<Long, NetworkLink[]> meshLinks = new HashMap<>();

	/**
	 * @param simulationManager       the testbed simulation manager
	 * @param computingNodesGenerator the generator of the nodes
	 */
	public TestbedTopologyCreator(SimulationManager simulationManager,
			ComputingNodesGenerator computingNodesGenerator) {
		super(simulationManager, computingNodesGenerator);
		this.world = ((TestbedSimulationManager) simulationManager).getWorld();
	}

	@Override
	public void generateTopologyGraph() {
		List<ComputingNode> nodes = computingNodesGenerator.getEdgeOnlyList();
		world.creator = this;
		buildMesh(nodes);

		ComputingNode cloud = world.cloud();
		boolean wanChart = false;
		for (EdgeServer server : world.servers())
			if (server.gateway) {
				NetworkLinkWanUp up = new NetworkLinkWanUp(server.node, cloud, simulationManager,
						NetworkLinkTypes.WAN);
				NetworkLinkWanDown down = new NetworkLinkWanDown(cloud, server.node, simulationManager,
						NetworkLinkTypes.WAN);
				infrastructureTopology.addLink(up);
				infrastructureTopology.addLink(down);
				if (!wanChart) {
					// The live WAN chart of the framework follows one uplink.
					simulationManager.getNetworkModel().setWanLinks(up, down);
					wanChart = true;
				}
			}

		for (Map.Entry<ComputingNode, EdgeServer> attachment : world.homes().entrySet()) {
			ComputingNode device = attachment.getKey();
			ComputingNode home = attachment.getValue().node;
			NetworkLink[] links = world.radioLinks(device, attachment.getValue());
			device.setCurrentLink(links[0], LinkOrientation.UP_LINK);
			device.setCurrentLink(links[1], LinkOrientation.DOWN_LINK);
			device.setCurrentLink(new NetworkLinkWifiDeviceToDevice(device, home, simulationManager,
					NetworkLinkTypes.LAN), LinkOrientation.DEVICE_TO_DEVICE);
			device.setOrchestrator(home);
		}

		world.topology = new LiveTopology(infrastructureTopology, cloud);
		getSimulationManager().getSimulationLogger().print(
				"%s - Mesh of %d nodes with %d links (average degree %.1f), %d gateways, %d access nodes",
				getClass().getSimpleName(), nodes.size(), world.meshPairs.size(),
				2.0 * world.meshPairs.size() / nodes.size(),
				world.servers().stream().filter(server -> server.gateway).count(), world.accessServers().size());
	}

	private void buildMesh(List<ComputingNode> nodes) {
		TestbedConfig config = world.config;
		for (int i = 0; i < nodes.size(); i++)
			for (int j = i + 1; j < nodes.size(); j++)
				if (distance(nodes.get(i), nodes.get(j)) <= config.linkRange)
					link(nodes.get(i), nodes.get(j));

		for (ComputingNode node : nodes) {
			List<ComputingNode> nearest = new ArrayList<>(nodes);
			nearest.remove(node);
			nearest.sort(Comparator.comparingDouble(other -> distance(node, other)));
			for (int k = 0; degree(node, nodes) < config.minDegree && k < nearest.size(); k++)
				link(node, nearest.get(k));
		}

		// Join the parts of the mesh until it is connected, each time by its shortest
		// missing link.
		while (true) {
			int[] part = parts(nodes);
			ComputingNode bestA = null;
			ComputingNode bestB = null;
			double best = Double.MAX_VALUE;
			for (int i = 0; i < nodes.size(); i++)
				for (int j = i + 1; j < nodes.size(); j++)
					if (part[i] != part[j] && distance(nodes.get(i), nodes.get(j)) < best) {
						best = distance(nodes.get(i), nodes.get(j));
						bestA = nodes.get(i);
						bestB = nodes.get(j);
					}
			if (bestA == null)
				return;
			link(bestA, bestB);
		}
	}

	private void link(ComputingNode a, ComputingNode b) {
		if (!linked.add(key(a, b)))
			return;
		double latency = latency(a, b);
		NetworkLink ab = new NetworkLinkMan(a, b, simulationManager, NetworkLinkTypes.MAN).setLatency(latency);
		NetworkLink ba = new NetworkLinkMan(b, a, simulationManager, NetworkLinkTypes.MAN).setLatency(latency);
		infrastructureTopology.addLink(ab);
		infrastructureTopology.addLink(ba);
		meshLinks.put(key(a, b), new NetworkLink[] { ab, ba });
		world.meshPairs.add(new ComputingNode[] { a, b });
		if (world.topology != null) {
			// A link that appears during the run can fail like any other.
			world.topology.linkAdded();
			world.injector.armMeshLink(a, b);
		}
	}

	private double latency(ComputingNode a, ComputingNode b) {
		return world.config.baseLatency + world.config.latencyPerKm * distance(a, b) / 1000;
	}

	/**
	 * Creates the radio links between a device and an access server, of the kind
	 * the connectivity of the device asks for. Created during the run, they are
	 * out of use until the device attaches to the server.
	 *
	 * @param device an end device
	 * @param server the edge data center of an access server
	 * @return the link from the device to the server, and the one back
	 */
	NetworkLink[] radio(ComputingNode device, ComputingNode server) {
		NetworkLink up = device.getCurrentLink(LinkOrientation.UP_LINK);
		NetworkLink down = device.getCurrentLink(LinkOrientation.DOWN_LINK);
		connect(device, server, NetworkLinkTypes.LAN);
		NetworkLink[] links = { device.getCurrentLink(LinkOrientation.UP_LINK),
				device.getCurrentLink(LinkOrientation.DOWN_LINK) };
		if (world.topology != null) {
			device.setCurrentLink(up, LinkOrientation.UP_LINK);
			device.setCurrentLink(down, LinkOrientation.DOWN_LINK);
			world.topology.linkAdded();
			for (NetworkLink link : links)
				world.topology.enable(link, false, world.now());
		}
		return links;
	}

	/**
	 * Makes the mesh follow the edge servers that moved: a link to a moving server
	 * is in use while the two servers are in range of each other and in the same
	 * group of every partition in progress.
	 */
	void updateMesh() {
		List<EdgeServer> servers = world.servers();
		LiveTopology topology = world.topology;
		for (int i = 0; i < servers.size(); i++)
			for (int j = i + 1; j < servers.size(); j++) {
				EdgeServer a = servers.get(i);
				EdgeServer b = servers.get(j);
				if (!a.mobile && !b.mobile)
					continue;
				boolean inRange = distance(a.node, b.node) <= world.config.linkRange && !world.separated(a, b);
				if (inRange)
					link(a.node, b.node);
				NetworkLink[] links = meshLinks.get(key(a.node, b.node));
				if (links == null)
					continue;
				double latency = latency(a.node, b.node);
				for (NetworkLink link : links) {
					topology.enable(link, inRange, world.now());
					if (inRange && Math.abs(link.getLatency() - latency) > 1e-9)
						topology.setLatency(link, latency);
				}
			}
	}

	private int degree(ComputingNode node, List<ComputingNode> nodes) {
		int degree = 0;
		for (ComputingNode other : nodes)
			if (other != node && linked.contains(key(node, other)))
				degree++;
		return degree;
	}

	/** Labels each node with the connected part of the mesh it belongs to. */
	private int[] parts(List<ComputingNode> nodes) {
		int[] parent = new int[nodes.size()];
		for (int i = 0; i < parent.length; i++)
			parent[i] = i;
		for (int i = 0; i < nodes.size(); i++)
			for (int j = i + 1; j < nodes.size(); j++)
				if (linked.contains(key(nodes.get(i), nodes.get(j))))
					parent[find(parent, i)] = find(parent, j);
		int[] part = new int[nodes.size()];
		for (int i = 0; i < part.length; i++)
			part[i] = find(parent, i);
		return part;
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i)
			i = parent[i] = parent[parent[i]];
		return i;
	}

	private static double distance(ComputingNode a, ComputingNode b) {
		return a.getMobilityModel().distanceTo(b);
	}

	private static long key(ComputingNode a, ComputingNode b) {
		int low = Math.min(a.getId(), b.getId());
		int high = Math.max(a.getId(), b.getId());
		return ((long) low << 32) | (high & 0xFFFFFFFFL);
	}
}
