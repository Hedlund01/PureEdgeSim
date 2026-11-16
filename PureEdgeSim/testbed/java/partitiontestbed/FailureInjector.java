package partitiontestbed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.network.NetworkLink;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationengine.OnSimulationStartListener;
import com.mechalikh.pureedgesim.simulationengine.SimEntity;
import com.mechalikh.pureedgesim.simulationmanager.SimLog;

import partitiontestbed.TestbedConfig.PartitionSpec;

/**
 * Breaks the network: the partitions of {@code testbed.properties}, and the
 * random failures of its links and servers.
 * <p>
 * <b>A partition</b> splits the edge servers into groups and cuts every link
 * between two groups. The groups are vertical bands of the map holding the same
 * number of servers ({@code geo:k}), random draws ({@code random:k}), or listed
 * by name ({@code dc1,dc2|dc3,dc4}, the servers not listed forming one more
 * group). At most one group keeps the uplinks of its gateways, and it reaches the
 * cloud only from the servers that still have a path to one of them. Devices stay
 * attached to their access server, so a group holds devices only when it holds
 * access servers. When it starts, the injector prints what each group holds.
 * <p>
 * <b>Random failures</b> take down a mesh link (both directions), the uplink of
 * a gateway, or a whole server with every link it has, after an exponential time
 * of mean one over the configured rate, and bring it back after an exponential
 * time of the configured mean. A server that fails keeps its state: it is cut
 * off, as by a power or network loss of its site, and it answers again when it
 * returns. Its devices attach to another access server in range meanwhile, when
 * {@code devices.failover} is on.
 * <p>
 * The injector draws from its own random source, so that the failures and the
 * partition groups are the same whatever the strategy under test draws.
 *
 * @see LiveTopology
 */
public class FailureInjector extends SimEntity implements OnSimulationStartListener {

	private static final int PARTITION_START = 1;
	private static final int PARTITION_END = 2;
	private static final int FAIL = 3;
	private static final int REPAIR = 4;
	private static final int SNAPSHOT = 5;

	/** What fails. */
	private enum Kind {
		LINK, WAN, NODE
	}

	/** Something that fails and is repaired as a whole. */
	private static final class Failable {
		final Kind kind;
		/** The links that fail, or none for a server, which fails as a node. */
		final List<NetworkLink> links;
		/** The server that fails, for {@link Kind#NODE}. */
		final ComputingNode node;
		final double rate;
		final double repair;

		Failable(Kind kind, List<NetworkLink> links, ComputingNode node, double rate, double repair) {
			this.kind = kind;
			this.links = links;
			this.node = node;
			this.rate = rate;
			this.repair = repair;
		}
	}

	/** A partition in progress, with the links it cut. */
	private static final class Cut {
		final TestbedWorld.ActivePartition partition;
		final List<NetworkLink> links;

		Cut(TestbedWorld.ActivePartition partition, List<NetworkLink> links) {
			this.partition = partition;
			this.links = links;
		}
	}

	private final TestbedWorld world;
	private final Random random;
	/** Random failures that happened, for the report. */
	int linkFailures;
	int wanFailures;
	int nodeFailures;

	FailureInjector(TestbedWorld world) {
		super(world.manager.getSimulation());
		this.world = world;
		// Its own source, so that the failures and the partition groups are the same
		// whatever the strategy draws.
		this.random = new Random(world.seed() ^ 0x9E3779B97F4A7C15L);
	}

	@Override
	public void onSimulationStart() {
		TestbedConfig config = world.config;
		for (PartitionSpec spec : config.partitions) {
			schedule(this, spec.start, PARTITION_START, spec);
			// The deployment map just before the cut, and half way through it.
			schedule(this, Math.max(0, spec.start - 0.001), SNAPSHOT, "partition_" + spec.index + "_before");
			schedule(this, spec.start + spec.duration / 2, SNAPSHOT, "partition_" + spec.index + "_during");
		}
		for (ComputingNode[] pair : new ArrayList<>(world.meshPairs))
			armMeshLink(pair[0], pair[1]);
		if (config.wanFailureRate > 0)
			for (EdgeServer server : world.servers())
				if (server.gateway)
					arm(new Failable(Kind.WAN, world.topology().linksBetween(server.node, world.cloud()), null,
							config.wanFailureRate, config.wanRepairTime));
		if (config.nodeFailureRate > 0)
			for (EdgeServer server : world.servers())
				arm(new Failable(Kind.NODE, List.of(), server.node, config.nodeFailureRate, config.nodeRepairTime));
	}

	/**
	 * Lets a link of the mesh fail at random: every link of the mesh at the start,
	 * and those that appear when a server moves.
	 *
	 * @param a one end of the link
	 * @param b its other end
	 */
	void armMeshLink(ComputingNode a, ComputingNode b) {
		if (world.config.linkFailureRate > 0)
			arm(new Failable(Kind.LINK, world.topology().linksBetween(a, b), null, world.config.linkFailureRate,
					world.config.linkRepairTime));
	}

	@Override
	protected void processEvent(Event e) {
		switch (e.getTag()) {
		case PARTITION_START:
			startPartition((PartitionSpec) e.getData());
			break;
		case PARTITION_END:
			Cut cut = (Cut) e.getData();
			world.topology().restore(cut.links);
			world.partitionEnded(cut.partition);
			world.updateAttachments();
			world.metrics.partitionEnded(cut.partition, world.now());
			SimLog.println("%s - %.0f s: partition %d healed", getClass().getSimpleName(), world.now(),
					cut.partition.spec.index);
			break;
		case FAIL:
			Failable failing = (Failable) e.getData();
			if (failing.kind == Kind.NODE)
				world.topology().fail(failing.node, world.now());
			else
				world.topology().cut(failing.links, world.now());
			world.updateAttachments();
			count(failing);
			schedule(this, exponential(1 / failing.repair), REPAIR, failing);
			break;
		case SNAPSHOT:
			world.manager.snapshot((String) e.getData());
			break;
		case REPAIR:
			Failable repaired = (Failable) e.getData();
			if (repaired.kind == Kind.NODE)
				world.topology().repair(repaired.node);
			else
				world.topology().restore(repaired.links);
			world.updateAttachments();
			arm(repaired);
			break;
		default:
			break;
		}
	}

	private void arm(Failable failable) {
		schedule(this, exponential(failable.rate), FAIL, failable);
	}

	private void count(Failable failable) {
		if (failable.kind == Kind.NODE)
			nodeFailures++;
		else if (failable.kind == Kind.WAN)
			wanFailures++;
		else
			linkFailures++;
	}

	private double exponential(double rate) {
		return -Math.log(1 - random.nextDouble()) / rate;
	}

	// ----------------------------------------------------------- partitions

	private void startPartition(PartitionSpec spec) {
		List<List<EdgeServer>> groups = groups(spec);
		Map<EdgeServer, Integer> groupOf = new LinkedHashMap<>();
		for (int g = 0; g < groups.size(); g++)
			for (EdgeServer server : groups.get(g))
				groupOf.put(server, g);
		Set<Integer> keepCloud = keepCloud(spec, groups.size());

		List<NetworkLink> links = new ArrayList<>();
		for (ComputingNode[] pair : world.meshPairs)
			if (!groupOf.get(world.serverOf(pair[0])).equals(groupOf.get(world.serverOf(pair[1]))))
				links.addAll(world.topology().linksBetween(pair[0], pair[1]));
		for (EdgeServer server : world.servers())
			if (server.gateway && !keepCloud.contains(groupOf.get(server)))
				links.addAll(world.topology().linksBetween(server.node, world.cloud()));
		world.topology().cut(links, world.now());

		Map<EdgeServer, Integer> devices = new HashMap<>();
		for (EdgeServer home : world.homes().values())
			devices.merge(home, 1, Integer::sum);
		List<Map<String, Integer>> mixes = new ArrayList<>();
		for (int g = 0; g < groups.size(); g++)
			mixes.add(new LinkedHashMap<>());
		for (TestbedWorld.DeviceType type : world.deviceTypes())
			for (Map<String, Integer> mix : mixes)
				mix.put(type.name, 0);
		for (Map.Entry<ComputingNode, EdgeServer> attachment : world.homes().entrySet())
			mixes.get(groupOf.get(attachment.getValue())).merge(world.deviceTypeOf(attachment.getKey()).name, 1,
					Integer::sum);
		List<TestbedMetrics.GroupRecord> records = new ArrayList<>();
		for (int g = 0; g < groups.size(); g++) {
			List<String> names = new ArrayList<>();
			int access = 0;
			int gateways = 0;
			int attached = 0;
			int withCloud = 0;
			for (EdgeServer server : groups.get(g)) {
				names.add(server.name());
				access += server.access ? 1 : 0;
				gateways += server.gateway ? 1 : 0;
				attached += devices.getOrDefault(server, 0);
				if (server.access && world.topology().reachable(server.node, world.cloud()))
					withCloud++;
			}
			StringBuilder mix = new StringBuilder();
			for (Map.Entry<String, Integer> type : mixes.get(g).entrySet())
				if (type.getValue() > 0)
					mix.append(mix.length() == 0 ? "" : " ").append(type.getKey()).append(':').append(type.getValue());
			records.add(new TestbedMetrics.GroupRecord(g, names, access, attached, gateways, withCloud,
					mix.length() == 0 ? "none" : mix.toString()));
		}
		TestbedWorld.ActivePartition partition = new TestbedWorld.ActivePartition(spec, groupOf,
				records);
		world.partitionStarted(partition);
		schedule(this, spec.duration, PARTITION_END, new Cut(partition, links));

		SimLog.println("%s - %.0f s: %s, %d links down", getClass().getSimpleName(), world.now(), spec,
				links.size());
		for (TestbedMetrics.GroupRecord group : records)
			SimLog.println("%s -     group %d: %d servers (%d access, %d gateways), %d devices (%s), cloud reachable "
					+ "from %d of %d access servers", getClass().getSimpleName(), group.index, group.nodes.size(),
					group.accessNodes, group.gateways, group.devices, group.deviceMix.replace(':', ' '),
					group.accessNodesWithCloud, group.accessNodes);
	}

	/**
	 * @return the groups of the partition, each holding at least one node
	 */
	private List<List<EdgeServer>> groups(PartitionSpec spec) {
		List<EdgeServer> servers = new ArrayList<>(world.servers());
		List<List<EdgeServer>> groups = new ArrayList<>();
		switch (spec.mode) {
		case GEO:
			servers.sort(Comparator.comparingDouble(
					(EdgeServer server) -> server.node.getMobilityModel().getCurrentLocation().getXPos()));
			for (int g = 0; g < spec.groupCount; g++)
				groups.add(new ArrayList<>(servers.subList(g * servers.size() / spec.groupCount,
						(g + 1) * servers.size() / spec.groupCount)));
			break;
		case RANDOM:
			Collections.shuffle(servers, random);
			for (int g = 0; g < spec.groupCount; g++)
				groups.add(new ArrayList<>());
			for (int i = 0; i < servers.size(); i++)
				groups.get(i % spec.groupCount).add(servers.get(i));
			break;
		default:
			Map<String, EdgeServer> byName = new HashMap<>();
			for (EdgeServer server : servers)
				byName.put(server.name(), server);
			Set<EdgeServer> placed = new HashSet<>();
			for (List<String> names : spec.explicitGroups) {
				List<EdgeServer> group = new ArrayList<>();
				for (String name : names) {
					EdgeServer server = byName.get(name);
					if (server == null)
						throw new IllegalArgumentException("FailureInjector - partition " + spec.index
								+ " names \"" + name + "\", which is not an edge node (dc1 to dc" + servers.size() + ")");
					if (!placed.add(server))
						throw new IllegalArgumentException("FailureInjector - partition " + spec.index
								+ " puts \"" + name + "\" in two groups");
					group.add(server);
				}
				groups.add(group);
			}
			List<EdgeServer> rest = new ArrayList<>(servers);
			rest.removeAll(placed);
			if (!rest.isEmpty())
				groups.add(rest);
			break;
		}
		return groups;
	}

	/**
	 * @return the one group that keeps its cloud uplinks, or none
	 */
	private Set<Integer> keepCloud(PartitionSpec spec, int groupCount) {
		switch (spec.cloud) {
		case "none":
			return Set.of();
		case "random":
			// Each group, or none of them, with the same chance.
			int drawn = random.nextInt(groupCount + 1);
			return drawn == groupCount ? Set.of() : Set.of(drawn);
		default:
			int group = Integer.parseInt(spec.cloud);
			if (group >= groupCount)
				throw new IllegalArgumentException("FailureInjector - partition " + spec.index
						+ " keeps the cloud for group " + group + ", but it only has " + groupCount + " groups");
			return Set.of(group);
		}
	}

}
