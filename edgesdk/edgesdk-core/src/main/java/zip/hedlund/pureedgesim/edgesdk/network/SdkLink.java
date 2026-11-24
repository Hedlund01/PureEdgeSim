package zip.hedlund.pureedgesim.edgesdk.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import zip.hedlund.pureedgesim.edgesdk.engine.SdkNode;
import zip.hedlund.pureedgesim.edgesdk.model.LinkRef;
import zip.hedlund.pureedgesim.edgesdk.model.LinkSpec;

/**
 * A bidirectional link at run time: two directed PureEdgeSim links and the
 * fault state they share.
 */
public final class SdkLink implements Link {

	private final LinkSpec spec;
	private final SdkNode a;
	private final SdkNode b;
	private final LiveNetwork network;
	private final EnumMap<DownCause, Integer> causes = new EnumMap<>(DownCause.class);
	private final List<Object[]> degradations = new ArrayList<>();
	SdkNetworkLink ab;
	SdkNetworkLink ba;

	public SdkLink(LinkSpec spec, SdkNode a, SdkNode b, LiveNetwork network) {
		this.spec = spec;
		this.a = a;
		this.b = b;
		this.network = network;
	}

	/** Attaches the two directions; called by the topology creator. */
	public void attach(SdkNetworkLink forward, SdkNetworkLink backward) {
		this.ab = forward;
		this.ba = backward;
	}

	public SdkNetworkLink forward() {
		return ab;
	}

	public SdkNetworkLink backward() {
		return ba;
	}

	public SdkNode a() {
		return a;
	}

	public SdkNode b() {
		return b;
	}

	@Override
	public LinkRef ref() {
		return spec.ref();
	}

	@Override
	public LinkSpec spec() {
		return spec;
	}

	@Override
	public LinkState state() {
		if (!causes.isEmpty())
			return LinkState.DOWN;
		return degradations.isEmpty() ? LinkState.UP : LinkState.DEGRADED;
	}

	@Override
	public Map<DownCause, Integer> downCauses() {
		return Collections.unmodifiableMap(new EnumMap<>(causes));
	}

	public boolean has(DownCause cause) {
		return causes.containsKey(cause);
	}

	@Override
	public Degradation degradation() {
		Degradation d = Degradation.NONE;
		for (Object[] token : degradations)
			d = d.combine((Degradation) token[0]);
		return d;
	}

	/** Adds one hold of a cause; the link goes down if it was up. */
	public void addCause(DownCause cause) {
		boolean wasUp = isUp();
		causes.merge(cause, 1, Integer::sum);
		if (wasUp) {
			if (ab != null)
				ab.failAll();
			if (ba != null)
				ba.failAll();
			network.changed();
		}
	}

	/** Removes one hold of a cause; the link comes back when no cause is left. */
	public void removeCause(DownCause cause) {
		Integer n = causes.get(cause);
		if (n == null)
			return;
		if (n <= 1)
			causes.remove(cause);
		else
			causes.put(cause, n - 1);
		if (isUp())
			network.changed();
	}

	/** Adds a degradation; returns a token to remove exactly this one. */
	public Object degrade(Degradation d) {
		Object[] token = { d };
		degradations.add(token);
		network.changed();
		return token;
	}

	public void restore(Object token) {
		degradations.remove(token);
		network.changed();
	}

	@Override
	public String toString() {
		return ref().toString();
	}
}
