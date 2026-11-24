package zip.hedlund.pureedgesim.edgesdk.network;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import zip.hedlund.pureedgesim.edgesdk.engine.SdkNode;
import zip.hedlund.pureedgesim.edgesdk.engine.SdkWorld;
import zip.hedlund.pureedgesim.edgesdk.model.ChannelSpec;
import zip.hedlund.pureedgesim.edgesdk.model.LossModel;

/**
 * Moves transfers through the live network: hop-by-hop routing, bandwidth
 * sharing (in {@link SdkNetworkLink}), latency, packet loss and retransmission,
 * loss when a link fails under a transfer, and per-bit energy.
 */
public final class NetworkEngine {

	private final SdkWorld world;
	private final LiveNetwork live;
	private final Random rng;
	private final EnumMap<LossReason, Long> lost = new EnumMap<>(LossReason.class);
	private final EnumMap<Transfer.Kind, Long> bytesSent = new EnumMap<>(Transfer.Kind.class);
	private long nextId;

	public NetworkEngine(SdkWorld world, LiveNetwork live, Random rng) {
		this.world = world;
		this.live = live;
		this.rng = rng;
	}

	public SdkWorld world() {
		return world;
	}

	/**
	 * Sends data. Exactly one of the callbacks runs, unless the run ends first.
	 *
	 * @return the transfer
	 */
	public Transfer send(SdkNode src, SdkNode dst, long bytes, Transfer.Kind kind, Consumer<Transfer> onDelivered,
			BiConsumer<Transfer, LossReason> onLost) {
		Transfer t = new Transfer(nextId++, src, dst, bytes, kind, onDelivered, onLost);
		bytesSent.merge(kind, t.bytes(), Long::sum);
		if (!src.isUp()) {
			lose(t, LossReason.ENDPOINT_DOWN);
			return t;
		}
		if (src == dst) {
			world.after(0, () -> deliver(t));
			return t;
		}
		forward(t);
		return t;
	}

	private void forward(Transfer t) {
		if (t.lost)
			return;
		if (t.at == t.dst) {
			deliver(t);
			return;
		}
		SdkNetworkLink hop = live.nextHop(t.at, t.dst);
		if (hop == null) {
			lose(t, t.dst.isUp() ? LossReason.NO_ROUTE : LossReason.ENDPOINT_DOWN);
			return;
		}
		hop.enqueue(t);
	}

	/** The last bit left {@code link}: apply loss, then propagate. */
	void hopTransmitted(Transfer t, SdkNetworkLink link) {
		double latency = link.currentLatency();
		ChannelSpec c = link.channel();
		double p = link.currentLoss();
		int packets = (int) Math.max(1, Math.ceil((double) t.bytes() / c.packetBytes()));
		int rounds = 0;
		boolean dropped = false;
		if (p > 0) {
			long missing = binomial(packets, p);
			if (c.lossModel() == LossModel.DROP) {
				dropped = missing > 0;
			} else {
				while (missing > 0) {
					rounds++;
					if (rounds > c.maxRetries()) {
						dropped = true;
						break;
					}
					missing = binomial(missing, p);
				}
			}
		}
		final boolean drop = dropped;
		// each retransmission round costs a round trip
		double delay = latency + rounds * 2 * latency;
		world.after(delay, () -> {
			if (!link.arrived(t) || t.lost)
				return;
			chargeEnergy(t, link);
			if (drop) {
				lose(t, LossReason.PACKET_LOSS);
				return;
			}
			t.at = link.to();
			t.hops++;
			forward(t);
		});
	}

	private void chargeEnergy(Transfer t, SdkNetworkLink link) {
		double joules = t.bits() * link.channel().joulesPerBit();
		link.from().sdkEnergy().addNetworkJoules(joules / 2);
		link.to().sdkEnergy().addNetworkJoules(joules / 2);
	}

	private void deliver(Transfer t) {
		if (t.lost || t.delivered)
			return;
		if (!t.dst.isUp()) {
			lose(t, LossReason.ENDPOINT_DOWN);
			return;
		}
		t.delivered = true;
		if (t.onDelivered != null)
			t.onDelivered.accept(t);
	}

	void lose(Transfer t, LossReason reason) {
		if (t.lost || t.delivered)
			return;
		t.lost = true;
		lost.merge(reason, 1L, Long::sum);
		if (t.onLost != null)
			t.onLost.accept(t, reason);
	}

	/** @return transfers lost so far, by reason */
	public Map<LossReason, Long> lostByReason() {
		return new EnumMap<>(lost);
	}

	public long bytesSent(Transfer.Kind kind) {
		return bytesSent.getOrDefault(kind, 0L);
	}

	private long binomial(long n, double p) {
		if (n <= 64) {
			long k = 0;
			for (long i = 0; i < n; i++)
				if (rng.nextDouble() < p)
					k++;
			return k;
		}
		double mean = n * p;
		if (mean < 30) { // Poisson approximation (Knuth)
			double l = Math.exp(-mean);
			long k = 0;
			double prod = rng.nextDouble();
			while (prod > l) {
				k++;
				prod *= rng.nextDouble();
			}
			return Math.min(k, n);
		}
		long k = Math.round(mean + Math.sqrt(mean * (1 - p)) * rng.nextGaussian());
		return Math.max(0, Math.min(n, k));
	}
}
