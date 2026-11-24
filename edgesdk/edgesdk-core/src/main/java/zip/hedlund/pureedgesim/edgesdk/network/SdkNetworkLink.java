package zip.hedlund.pureedgesim.edgesdk.network;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mechalikh.pureedgesim.energy.EnergyModelNetworkLink;
import com.mechalikh.pureedgesim.network.NetworkLink;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;

import zip.hedlund.pureedgesim.edgesdk.engine.SdkNode;
import zip.hedlund.pureedgesim.edgesdk.model.ChannelSpec;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;

/**
 * One direction of a link, as a PureEdgeSim {@link NetworkLink} (so it lives in
 * the core infrastructure graph and energy accounting) with the SDK transfer
 * model: the bandwidth of this instance's channel, shared equally between the
 * transfers on it and advanced every {@code network_update_interval}; latency from
 * the channel, the current distance and any degradation.
 */
public final class SdkNetworkLink extends NetworkLink {

	private final SdkLink parent;
	private final boolean forwardDirection;
	private final SdkNode from;
	private final SdkNode to;
	private final NetworkEngine engine;
	private final List<Transfer> sending = new ArrayList<>();
	private final Set<Transfer> propagating = new LinkedHashSet<>();
	private boolean ticking;

	public SdkNetworkLink(SdkNode from, SdkNode to, SdkLink parent, boolean forwardDirection,
			SimulationManager simulationManager, NetworkEngine engine) {
		super(from, to, simulationManager, coreType(from, to));
		this.parent = parent;
		this.forwardDirection = forwardDirection;
		this.from = from;
		this.to = to;
		this.engine = engine;
		ChannelSpec c = parent.spec().channel();
		setBandwidth((forwardDirection ? c.upMbps() : c.downMbps()) * 1e6);
		setLatency(designLatency());
		setEnergyModel(new EnergyModelNetworkLink(c.joulesPerBit() / 3600.0, this));
	}

	private static NetworkLinkTypes coreType(SdkNode from, SdkNode to) {
		Tier x = from.decl().tier();
		Tier y = to.decl().tier();
		if (x == Tier.CLOUD || y == Tier.CLOUD)
			return NetworkLinkTypes.WAN;
		if (x == Tier.END_DEVICE || y == Tier.END_DEVICE)
			return NetworkLinkTypes.LAN;
		return NetworkLinkTypes.MAN;
	}

	public SdkLink parent() {
		return parent;
	}

	public SdkNode from() {
		return from;
	}

	public SdkNode to() {
		return to;
	}

	public ChannelSpec channel() {
		return parent.spec().channel();
	}

	/** @return current bandwidth in bit/s, degradation included */
	public double bandwidthBps() {
		ChannelSpec c = channel();
		return (forwardDirection ? c.upMbps() : c.downMbps()) * 1e6 * parent.degradation().bandwidthFactor();
	}

	private double designLatency() {
		ChannelSpec c = channel();
		double km = from.decl().position().distanceTo(to.decl().position()) / 1000.0;
		return (c.latencyMs() + c.latencyPerKmMs() * km) / 1000.0;
	}

	/** @return current one-way latency in seconds */
	public double currentLatency() {
		ChannelSpec c = channel();
		double km = from.distanceTo(to) / 1000.0;
		return (c.latencyMs() + c.latencyPerKmMs() * km) / 1000.0 + parent.degradation().extraLatencyS();
	}

	/** @return packet loss probability now */
	public double currentLoss() {
		return 1 - (1 - channel().packetLoss()) * (1 - parent.degradation().extraLoss());
	}

	public int activeTransfers() {
		return sending.size();
	}

	void enqueue(Transfer t) {
		t.remainingBits = t.bits();
		sending.add(t);
		totalTrasferredData += t.bits();
		if (!ticking) {
			ticking = true;
			schedule(this, SimulationParameters.networkUpdateInterval, UPDATE_PROGRESS);
		}
	}

	@Override
	public void processEvent(Event evt) {
		if (evt.getTag() != UPDATE_PROGRESS)
			return;
		engine.world().guard(this::tick);
	}

	private void tick() {
		if (sending.isEmpty()) {
			ticking = false;
			usedBandwidth = 0;
			return;
		}
		double dt = SimulationParameters.networkUpdateInterval;
		double share = bandwidthBps() / sending.size();
		usedBandwidth = bandwidthBps();
		for (Transfer t : new ArrayList<>(sending)) {
			t.remainingBits -= share * dt;
			if (t.remainingBits <= 0) {
				sending.remove(t);
				propagating.add(t);
				engine.hopTransmitted(t, this);
			}
		}
		if (sending.isEmpty()) {
			ticking = false;
			usedBandwidth = 0;
		} else {
			schedule(this, dt, UPDATE_PROGRESS);
		}
	}

	/** @return true when the transfer was still on this link (not failed meanwhile) */
	boolean arrived(Transfer t) {
		return propagating.remove(t);
	}

	/** The link went down: everything on it is lost. */
	void failAll() {
		List<Transfer> victims = new ArrayList<>(sending);
		victims.addAll(propagating);
		sending.clear();
		propagating.clear();
		for (Transfer t : victims)
			engine.lose(t, LossReason.LINK_LOST);
	}

	@Override
	public String toString() {
		return from + "->" + to + "(" + channel().name() + ")";
	}
}
