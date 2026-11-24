package zip.hedlund.pureedgesim.edgesdk.fault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.engine.SdkNode;
import zip.hedlund.pureedgesim.edgesdk.engine.SdkWorld;
import zip.hedlund.pureedgesim.edgesdk.model.LinkRef;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.network.Degradation;
import zip.hedlund.pureedgesim.edgesdk.network.DownCause;
import zip.hedlund.pureedgesim.edgesdk.network.SdkLink;

/**
 * Applies faults to the live network and writes them to the {@link FaultLog}.
 * A partition adds the cause {@link DownCause#PARTITION} to every link between
 * two groups and removes exactly those causes when it heals.
 */
public final class FaultInjector implements FaultScheduler {

	private final SdkWorld world;
	private final String source;

	public FaultInjector(SdkWorld world, String source) {
		this.world = world;
		this.source = source;
	}

	@Override
	public void linkDown(LinkSelect target, double at, double duration) {
		world.at(at, () -> {
			for (LinkRef ref : target.resolve(world.design())) {
				SdkLink link = world.live().link(ref);
				link.addCause(DownCause.FAULT);
				int id = world.faultLog().open(FaultType.LINK_DOWN, ref.toString(), source, world.now(),
						List.of(ref.a(), ref.b()), "");
				if (duration != Double.POSITIVE_INFINITY)
					world.after(duration, () -> {
						link.removeCause(DownCause.FAULT);
						world.faultLog().close(id, world.now());
					});
			}
		});
	}

	@Override
	public void degrade(LinkSelect target, double at, double duration, Degradation degradation) {
		world.at(at, () -> {
			for (LinkRef ref : target.resolve(world.design())) {
				SdkLink link = world.live().link(ref);
				Object token = link.degrade(degradation);
				int id = world.faultLog().open(FaultType.LINK_DEGRADED, ref.toString(), source, world.now(),
						List.of(ref.a(), ref.b()), "");
				if (duration != Double.POSITIVE_INFINITY)
					world.after(duration, () -> {
						link.restore(token);
						world.faultLog().close(id, world.now());
					});
			}
		});
	}

	@Override
	public void crash(Select target, double at, Recovery recovery) {
		world.at(at, () -> {
			for (NodeRef ref : target.resolve(world.design())) {
				SdkNode node = world.node(ref);
				if (node.isPermanentlyDown())
					continue;
				world.crash(node, !recovery.recovers());
				int id = world.faultLog().open(recovery.recovers() ? FaultType.CRASH_RECOVERY : FaultType.CRASH_STOP,
						ref.name(), source, world.now(), List.of(ref.name()), "");
				if (recovery.recovers())
					world.after(recovery.after(), () -> {
						world.recover(node);
						world.faultLog().close(id, world.now());
					});
			}
		});
	}

	@Override
	public void partition(List<Select> groups, double at, double duration) {
		world.at(at, () -> {
			Map<NodeRef, Integer> groupOf = new HashMap<>();
			List<Set<String>> members = new ArrayList<>();
			for (Select g : groups) {
				Set<String> set = new LinkedHashSet<>();
				for (NodeRef n : g.resolve(world.design()))
					if (groupOf.putIfAbsent(n, members.size()) == null)
						set.add(n.name());
				members.add(set);
			}
			Set<String> rest = new LinkedHashSet<>();
			for (var n : world.design().nodes())
				if (!groupOf.containsKey(n.ref())) {
					groupOf.put(n.ref(), members.size());
					rest.add(n.name());
				}
			if (!rest.isEmpty())
				members.add(rest);
			List<SdkLink> cut = new ArrayList<>();
			for (SdkLink l : world.live().links())
				if (!groupOf.get(l.a().ref()).equals(groupOf.get(l.b().ref())))
					cut.add(l);
			for (SdkLink l : cut)
				l.addCause(DownCause.PARTITION);
			String text = members.stream().map(s -> String.join(",", s)).collect(Collectors.joining("|"));
			List<String> all = members.stream().flatMap(Set::stream).toList();
			int id = world.faultLog().open(FaultType.PARTITION, members.size() + " groups", source, world.now(), all,
					text);
			if (duration != Double.POSITIVE_INFINITY)
				world.after(duration, () -> {
					for (SdkLink l : cut)
						l.removeCause(DownCause.PARTITION);
					world.faultLog().close(id, world.now());
				});
		});
	}

	@Override
	public void at(double time, Runnable action) {
		world.at(time, action);
	}

	@Override
	public double now() {
		return world.now();
	}

	@Override
	public double duration() {
		return world.duration();
	}
}
