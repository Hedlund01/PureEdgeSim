package partitiontestbed;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * How a strategy uses the network the testbed gives it: which kinds of links it
 * sends traffic over, which nodes forward traffic for others, whether a device
 * talks to every access server it hears, and whether a radio contact between two
 * partition groups may join them.
 * <p>
 * The testbed decides what exists, the same for every strategy: the wired mesh
 * of the edge servers, the uplinks of the gateways, the radio of each device
 * ({@code devices.radio_range_m}), the radio between devices
 * ({@code d2d.range_m}), the links to a moving server, and what the failures and
 * the partitions take away. The policy decides what of it the strategy's
 * protocol uses, as a design choice of the algorithm: a strict hierarchy, a mesh
 * within a layer, a mesh across layers, multi-hop relaying by devices, or none of
 * it. Every message, task and result of the strategy is routed on the shortest
 * live path the policy allows, and is lost when there is none.
 * <p>
 * A link is used when the policy uses links between the layers of its two ends
 * ({@link #link}). A path goes through a node, rather than starting or ending at
 * it, only when that node relays: by default when its layer does
 * ({@link #relay}), and otherwise as the strategy sets it on that node
 * ({@link StrategyContext#setRelay}), for example to make a cluster head of a
 * device.
 * <p>
 * {@link #hierarchical()} is the default, and what the testbed did before this
 * class existed: a device talks to its access server, the edge servers form a
 * mesh and reach the cloud through the gateways, only edge servers relay, and the
 * cloud is the end of a path, never a hop of one.
 * <p>
 * The policy is read once, when the topology is built; changing it later has no
 * effect.
 *
 * @see OrchestrationStrategy#network()
 * @see LiveTopology
 */
public final class NetworkPolicy {

	/** The layers of the continuum. */
	public enum Layer {
		/** The end devices: sensors, cameras, vehicles, drones. */
		DEVICE,
		/** The edge servers. */
		EDGE,
		/** The cloud data center. */
		CLOUD
	}

	private final boolean[][] links = new boolean[Layer.values().length][Layer.values().length];
	private final Set<Layer> relays = EnumSet.noneOf(Layer.class);
	private boolean multiHoming;
	private boolean bridging;

	/** A policy that uses nothing: add what the strategy uses. */
	public NetworkPolicy() {
	}

	/**
	 * @return a policy that uses the links between devices and edge servers, within
	 *         the edge layer and between the edge and the cloud, in which only edge
	 *         servers relay, and in which each device talks to its access server
	 *         only
	 */
	public static NetworkPolicy hierarchical() {
		return new NetworkPolicy().link(Layer.DEVICE, Layer.EDGE).link(Layer.EDGE, Layer.EDGE)
				.link(Layer.EDGE, Layer.CLOUD).relay(Layer.EDGE);
	}

	/**
	 * @return {@link #hierarchical()} with the radio links between devices in use,
	 *         and devices relaying for each other: a device out of range of every
	 *         access server can still reach one through other devices
	 */
	public static NetworkPolicy deviceMesh() {
		return hierarchical().link(Layer.DEVICE, Layer.DEVICE).relay(Layer.DEVICE);
	}

	/**
	 * Uses, or stops using, the links between two layers, in both directions.
	 *
	 * @param a    one layer
	 * @param b    another layer, or the same one for a mesh within it
	 * @param used whether the strategy sends traffic over them
	 * @return this policy
	 */
	public NetworkPolicy link(Layer a, Layer b, boolean used) {
		links[a.ordinal()][b.ordinal()] = used;
		links[b.ordinal()][a.ordinal()] = used;
		return this;
	}

	/**
	 * Uses the links between two layers.
	 *
	 * @param a one layer
	 * @param b another layer, or the same one for a mesh within it
	 * @return this policy
	 */
	public NetworkPolicy link(Layer a, Layer b) {
		return link(a, b, true);
	}

	/**
	 * Lets the nodes of a layer forward traffic for others, or stops them.
	 *
	 * @param layer   the layer
	 * @param relaying whether its nodes are hops of paths
	 * @return this policy
	 */
	public NetworkPolicy relay(Layer layer, boolean relaying) {
		if (relaying)
			relays.add(layer);
		else
			relays.remove(layer);
		return this;
	}

	/**
	 * Lets the nodes of a layer forward traffic for others.
	 *
	 * @param layer the layer
	 * @return this policy
	 */
	public NetworkPolicy relay(Layer layer) {
		return relay(layer, true);
	}

	/**
	 * @param on whether a device keeps a radio link to every access server in
	 *           range that answers, not only to the one it is attached to. Its
	 *           attachment still says which server it sends its tasks to by default
	 *           and which group it is in; the other links carry whatever is routed
	 *           over them.
	 * @return this policy
	 */
	public NetworkPolicy multiHoming(boolean on) {
		this.multiHoming = on;
		return this;
	}

	/**
	 * @param on whether a radio link between two nodes that a partition puts in
	 *           two groups carries the strategy's traffic, joining the groups
	 *           through it. Only where {@code partitions.cross_group_radio} lets such
	 *           a link exist: otherwise a partition isolates its groups whatever the
	 *           policy. The wired links a partition cuts stay cut either way.
	 * @return this policy
	 */
	public NetworkPolicy bridgeGroups(boolean on) {
		this.bridging = on;
		return this;
	}

	/**
	 * @param a one layer
	 * @param b another
	 * @return whether the strategy uses the links between them
	 */
	public boolean uses(Layer a, Layer b) {
		return links[a.ordinal()][b.ordinal()];
	}

	/**
	 * @param layer a layer
	 * @return whether its nodes relay, unless the strategy sets otherwise on a node
	 */
	public boolean relays(Layer layer) {
		return relays.contains(layer);
	}

	/** @return whether a device uses every access server in range */
	public boolean multiHoming() {
		return multiHoming;
	}

	/** @return whether a radio link between two groups may join them */
	public boolean bridgesGroups() {
		return bridging;
	}

	@Override
	public String toString() {
		List<String> used = new ArrayList<>();
		Layer[] layers = Layer.values();
		for (int i = 0; i < layers.length; i++)
			for (int j = i; j < layers.length; j++)
				if (links[i][j])
					used.add(name(layers[i]) + "-" + name(layers[j]));
		List<String> relaying = new ArrayList<>();
		for (Layer layer : relays)
			relaying.add(name(layer));
		return "links " + (used.isEmpty() ? "none" : String.join(" ", used)) + ", relays "
				+ (relaying.isEmpty() ? "none" : String.join(" ", relaying)) + (multiHoming ? ", multi-homing" : "")
				+ (bridging ? ", bridges groups" : "");
	}

	private static String name(Layer layer) {
		return layer.name().toLowerCase();
	}
}
