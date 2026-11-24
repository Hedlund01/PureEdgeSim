package zip.hedlund.pureedgesim.edgesdk.network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zip.hedlund.pureedgesim.edgesdk.fault.FaultLog;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultType;

/**
 * Emergent faults: a wireless link goes down (cause
 * {@link DownCause#OUT_OF_RANGE}) when its endpoints are farther apart than the
 * channel's range, and comes back when they are in range again. Checked every
 * {@code update_interval}, after the nodes have moved. Logged with cause
 * {@code mobility}.
 */
public final class RangeMonitor {

	private final List<SdkLink> wireless;
	private final FaultLog log;
	private final Map<SdkLink, Integer> open = new HashMap<>();

	public RangeMonitor(LiveNetwork network, FaultLog log) {
		this.wireless = network.links().stream().filter(l -> l.spec().channel().isWireless()).toList();
		this.log = log;
	}

	/** Re-evaluates every wireless link at time {@code now}. */
	public void check(double now) {
		for (SdkLink l : wireless) {
			boolean out = l.a().distanceTo(l.b()) > l.spec().channel().rangeM();
			boolean marked = l.has(DownCause.OUT_OF_RANGE);
			if (out && !marked) {
				l.addCause(DownCause.OUT_OF_RANGE);
				open.put(l, log.open(FaultType.LINK_DOWN, l.ref().toString(), "mobility", now,
						List.of(l.a().ref().name(), l.b().ref().name()), ""));
			} else if (!out && marked) {
				l.removeCause(DownCause.OUT_OF_RANGE);
				Integer id = open.remove(l);
				if (id != null)
					log.close(id, now);
			}
		}
	}
}
