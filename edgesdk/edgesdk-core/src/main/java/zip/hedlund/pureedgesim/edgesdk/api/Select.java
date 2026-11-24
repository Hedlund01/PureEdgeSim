package zip.hedlund.pureedgesim.edgesdk.api;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.NodeDecl;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;

/**
 * Selects nodes of a scenario: by name (with {@code *} wildcards), tier or group.
 * Selections are resolved against the design topology, so the same selection can
 * target links, faults and storage hosts.
 */
@FunctionalInterface
public interface Select {

	/** @return the selected nodes, in declaration order */
	Set<NodeRef> resolve(DesignTopology topology);

	/** Every node. */
	static Select all() {
		return t -> collect(t, n -> true);
	}

	/** Nodes whose name matches a pattern, where {@code *} matches anything. */
	static Select name(String pattern) {
		Pattern regex = glob(pattern);
		return t -> collect(t, n -> regex.matcher(n.name()).matches());
	}

	/** Nodes matching any of the patterns or group names. */
	static Select names(String... patternsOrGroups) {
		return t -> {
			Set<NodeRef> out = new LinkedHashSet<>();
			for (String p : patternsOrGroups) {
				if (t.groups().containsKey(p))
					out.addAll(group(p).resolve(t));
				else
					out.addAll(name(p).resolve(t));
			}
			return out;
		};
	}

	/** Nodes of the given tiers. */
	static Select tier(Tier... tiers) {
		EnumSet<Tier> set = EnumSet.copyOf(Arrays.asList(tiers));
		return t -> collect(t, n -> set.contains(n.tier()));
	}

	/** Every edge node, static or mobile. */
	static Select edges() {
		return tier(Tier.EDGE_STATIC, Tier.EDGE_MOBILE);
	}

	/** The members of a declared group. */
	static Select group(String group) {
		return t -> collect(t, n -> t.group(group).members().contains(n.name()));
	}

	/** Nodes selected by this or the other selection. */
	default Select or(Select other) {
		return t -> {
			Set<NodeRef> out = new LinkedHashSet<>(resolve(t));
			out.addAll(other.resolve(t));
			return out;
		};
	}

	/** Nodes selected by this selection but not the other. */
	default Select except(Select other) {
		return t -> {
			Set<NodeRef> out = new LinkedHashSet<>(resolve(t));
			out.removeAll(other.resolve(t));
			return out;
		};
	}

	private static Set<NodeRef> collect(DesignTopology t, java.util.function.Predicate<NodeDecl> p) {
		Set<NodeRef> out = new LinkedHashSet<>();
		for (NodeDecl n : t.nodes())
			if (p.test(n))
				out.add(n.ref());
		return out;
	}

	private static Pattern glob(String pattern) {
		String[] parts = pattern.split("\\*", -1);
		StringBuilder rx = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0)
				rx.append(".*");
			rx.append(Pattern.quote(parts[i]));
		}
		return Pattern.compile(rx.toString());
	}
}
