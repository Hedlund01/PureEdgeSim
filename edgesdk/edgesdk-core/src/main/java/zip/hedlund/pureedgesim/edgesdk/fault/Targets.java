package zip.hedlund.pureedgesim.edgesdk.fault;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.model.ChannelSpec;
import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.LinkRef;
import zip.hedlund.pureedgesim.edgesdk.model.LinkSpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;

/**
 * Fault targets: nodes (as {@link Select}) and links (as {@link LinkSelect}).
 *
 * <pre>{@code
 * Targets.node("uav-3")
 * Targets.nodes(Tier.CLOUD)
 * Targets.link("truck", "cloud-eu")
 * Targets.links().channel(ChannelPresets.LORA)
 * Targets.links().between(Select.group("regionA"), Select.tier(Tier.CLOUD))
 * }</pre>
 */
public final class Targets {

	private Targets() {
	}

	/** A node by name or pattern. */
	public static Select node(String nameOrPattern) {
		return Select.name(nameOrPattern);
	}

	/** Every node of the given tiers. */
	public static Select nodes(Tier... tiers) {
		return Select.tier(tiers);
	}

	/** The members of a group. */
	public static Select group(String name) {
		return Select.group(name);
	}

	/** Every link between two nodes, whatever the channel. */
	public static LinkSelect link(String a, String b) {
		return links().where(l -> l.ref().joins(a, b));
	}

	/** The link between two nodes over one channel. */
	public static LinkSelect link(String a, String b, String channel) {
		return links().where(l -> l.ref().joins(a, b) && l.channel().name().equals(channel));
	}

	/** A query over every link, refined by its methods. */
	public static LinkQuery links() {
		return new LinkQuery(l -> true, null);
	}

	/** A composable link query. */
	public static final class LinkQuery implements LinkSelect {

		private final Predicate<LinkSpec> filter;
		private final java.util.function.BiPredicate<LinkSpec, DesignTopology> topologyFilter;

		private LinkQuery(Predicate<LinkSpec> filter,
				java.util.function.BiPredicate<LinkSpec, DesignTopology> topologyFilter) {
			this.filter = filter;
			this.topologyFilter = topologyFilter;
		}

		public LinkQuery where(Predicate<LinkSpec> p) {
			return new LinkQuery(filter.and(p), topologyFilter);
		}

		/** Links over a channel (by name). */
		public LinkQuery channel(ChannelSpec channel) {
			return where(l -> l.channel().name().equals(channel.name()));
		}

		/** Links with at least one endpoint selected. */
		public LinkQuery touching(Select nodes) {
			return withTopology((l, t) -> {
				Set<NodeRef> s = nodes.resolve(t);
				return s.contains(l.a()) || s.contains(l.b());
			});
		}

		/** Links with one endpoint in each selection. */
		public LinkQuery between(Select x, Select y) {
			return withTopology((l, t) -> {
				Set<NodeRef> sx = x.resolve(t);
				Set<NodeRef> sy = y.resolve(t);
				return (sx.contains(l.a()) && sy.contains(l.b())) || (sx.contains(l.b()) && sy.contains(l.a()));
			});
		}

		private LinkQuery withTopology(java.util.function.BiPredicate<LinkSpec, DesignTopology> p) {
			return new LinkQuery(filter, topologyFilter == null ? p : topologyFilter.and(p));
		}

		@Override
		public Set<LinkRef> resolve(DesignTopology topology) {
			Set<LinkRef> out = new LinkedHashSet<>();
			for (LinkSpec l : topology.links())
				if (filter.test(l) && (topologyFilter == null || topologyFilter.test(l, topology)))
					out.add(l.ref());
			return out;
		}
	}
}
