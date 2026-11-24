package zip.hedlund.pureedgesim.edgesdk.storage.strategies;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import zip.hedlund.pureedgesim.edgesdk.algorithm.Response;
import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.storage.ReadRequest;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageOps;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageStrategy;
import zip.hedlund.pureedgesim.edgesdk.storage.VectorClock;
import zip.hedlund.pureedgesim.edgesdk.storage.VersionedValue;
import zip.hedlund.pureedgesim.edgesdk.storage.WriteRequest;

/**
 * Eventual consistency (availability over consistency): every host holds every
 * key. A client writes to and reads from the nearest host that answers, trying
 * the next one on timeout, and the hosts reconcile by anti-entropy gossip:
 * every {@code period} seconds each host exchanges digests with {@code fanout}
 * random peers and both sides pull what they miss (last writer wins). Writes
 * stay available on both sides of a partition; replicas converge after it heals.
 */
public class EventualGossip extends StorageStrategy {

	record Put(VersionedValue value) {
	}

	record Get(String key) {
	}

	/** Digest: key to writeId and time of what the sender holds. */
	record Digest(Map<String, double[]> entries) {
	}

	/** Answer to a digest: values the receiver had newer, and keys it wants. */
	record Delta(List<VersionedValue> values, List<String> wanted) {
	}

	private final double period;
	private final int fanout;
	private final double attemptTimeout;

	public EventualGossip() {
		this(2.0, 1, 0.5);
	}

	/**
	 * @param period         seconds between gossip rounds
	 * @param fanout         peers contacted per round
	 * @param attemptTimeout client timeout before trying the next host
	 */
	public EventualGossip(double period, int fanout, double attemptTimeout) {
		this.period = period;
		this.fanout = fanout;
		this.attemptTimeout = attemptTimeout;
	}

	@Override
	public void onStart(SdkContext ctx) {
		for (NodeRef host : ops.storageNodes())
			startGossip(host);
	}

	@Override
	public void onNodeRestarted(NodeRef at) {
		if (ops.storageNodes().contains(at))
			startGossip(at);
	}

	private void startGossip(NodeRef host) {
		ctx.schedule(host, ctx.rng().nextDouble() * period, () -> {
			gossip(host);
			ctx.every(host, period, () -> gossip(host));
		});
	}

	private void gossip(NodeRef host) {
		List<NodeRef> peers = new ArrayList<>(ops.storageNodes());
		peers.remove(host);
		if (peers.isEmpty())
			return;
		Map<String, double[]> digest = new HashMap<>();
		for (String k : ops.keys(host)) {
			VersionedValue v = ops.local(host, k).orElseThrow();
			digest.put(k, new double[] { v.writeId(), v.writtenAt() });
		}
		for (int i = 0; i < Math.min(fanout, peers.size()); i++) {
			NodeRef peer = peers.remove(ctx.rng().nextInt(peers.size()));
			ctx.request(host, peer, 32 + 24L * digest.size(), new Digest(digest), period, reply -> {
				Delta d = (Delta) reply.payload();
				d.values().forEach(v -> store(host, v));
				long bytes = 32;
				List<VersionedValue> push = new ArrayList<>();
				for (String k : d.wanted())
					ops.local(host, k).ifPresent(push::add);
				for (VersionedValue v : push)
					bytes += v.bytes() + 32;
				if (!push.isEmpty())
					ctx.send(host, peer, bytes, new Delta(push, List.of()));
			}, () -> {
			});
		}
	}

	@Override
	public void onWrite(NodeRef at, WriteRequest w, StorageOps ops) {
		VersionedValue v = w.version(VectorClock.EMPTY.increment(at.name()));
		tryHosts(at, order(at), 0, w.deadline(), new Put(v), v.bytes() + 64, reply -> ops.ack(w, true));
	}

	@Override
	public void onRead(NodeRef at, ReadRequest r, StorageOps ops) {
		tryHosts(at, order(at), 0, r.deadline(), new Get(r.key()), 64, reply -> {
			@SuppressWarnings("unchecked")
			Optional<VersionedValue> v = (Optional<VersionedValue>) reply;
			ops.reply(r, v);
		});
	}

	private List<NodeRef> order(NodeRef client) {
		return ctx.topology().byDesignLatency(client, ops.storageNodes());
	}

	private void tryHosts(NodeRef at, List<NodeRef> hosts, int i, double deadline, Object request, long bytes,
			java.util.function.Consumer<Object> onReply) {
		double left = deadline - ctx.now();
		if (i >= hosts.size() || left <= 0)
			return;
		ctx.request(at, hosts.get(i), bytes, request, Math.min(attemptTimeout, left),
				reply -> onReply.accept(reply.payload()),
				() -> tryHosts(at, hosts, i + 1, deadline, request, bytes, onReply));
	}

	@Override
	public Object onRequest(NodeRef at, NodeRef from, Object request) {
		if (request instanceof Put p) {
			store(at, p.value());
			return true;
		}
		if (request instanceof Get g) {
			Optional<VersionedValue> v = ops.local(at, g.key());
			return Response.of(v, 64 + v.map(VersionedValue::bytes).orElse(0L));
		}
		if (request instanceof Digest d) {
			List<VersionedValue> newer = new ArrayList<>();
			List<String> wanted = new ArrayList<>();
			long bytes = 32;
			for (String k : ops.keys(at)) {
				VersionedValue mine = ops.local(at, k).orElseThrow();
				double[] theirs = d.entries().get(k);
				if (theirs == null || mine.writtenAt() > theirs[1]
						|| (mine.writtenAt() == theirs[1] && mine.writeId() > theirs[0])) {
					newer.add(mine);
					bytes += mine.bytes() + 32;
				}
			}
			for (Map.Entry<String, double[]> e : d.entries().entrySet()) {
				Optional<VersionedValue> mine = ops.local(at, e.getKey());
				double[] t = e.getValue();
				if (mine.isEmpty() || t[1] > mine.get().writtenAt()
						|| (t[1] == mine.get().writtenAt() && t[0] > mine.get().writeId())) {
					wanted.add(e.getKey());
					bytes += 16;
				}
			}
			return Response.of(new Delta(newer, wanted), bytes);
		}
		return super.onRequest(at, from, request);
	}

	@Override
	public void onMessage(NodeRef at, NodeRef from, Object payload) {
		if (payload instanceof Delta d)
			d.values().forEach(v -> store(at, v));
	}

	private void store(NodeRef at, VersionedValue v) {
		VersionedValue mine = ops.local(at, v.key()).orElse(null);
		if (v.newerThan(mine))
			ops.storeLocal(at, mine == null ? v : v.withClock(v.clock().merge(mine.clock())));
	}
}
