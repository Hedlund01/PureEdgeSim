package zip.hedlund.pureedgesim.edgesdk.storage.strategies;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import zip.hedlund.pureedgesim.edgesdk.algorithm.Response;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.storage.ReadRequest;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageOps;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageStrategy;
import zip.hedlund.pureedgesim.edgesdk.storage.VectorClock;
import zip.hedlund.pureedgesim.edgesdk.storage.VersionedValue;
import zip.hedlund.pureedgesim.edgesdk.storage.WriteRequest;

/**
 * Dynamo-style quorum replication: each key lives on {@code n} hosts; the client
 * coordinates, a write succeeds with {@code w} acknowledgements and a read
 * returns the newest of {@code r} answers (last writer wins) and repairs stale
 * replicas. With {@code r + w > n} reads see the latest acknowledged write; on the
 * minority side of a partition, operations fail.
 */
public class Quorum extends StorageStrategy {

	record Put(VersionedValue value) {
	}

	record Get(String key) {
	}

	private final int n;
	private final int r;
	private final int w;

	public Quorum(int n, int r, int w) {
		if (n < 1 || r < 1 || w < 1 || r > n || w > n)
			throw new IllegalArgumentException("need 1 <= r, w <= n");
		this.n = n;
		this.r = r;
		this.w = w;
	}

	@Override
	public void onWrite(NodeRef at, WriteRequest req, StorageOps ops) {
		List<NodeRef> replicas = Replicas.forKey(ops, req.key(), n);
		VersionedValue v = req.version(VectorClock.EMPTY.increment(at.name()));
		int[] acks = { 0 };
		int[] answered = { 0 };
		boolean[] done = { false };
		int needed = Math.min(w, replicas.size());
		for (NodeRef rep : replicas)
			ctx.request(at, rep, v.bytes() + 64, new Put(v), req.deadline() - ctx.now(), reply -> {
				acks[0]++;
				answered[0]++;
				if (!done[0] && acks[0] >= needed) {
					done[0] = true;
					ops.ack(req, true);
				}
			}, () -> {
				answered[0]++;
				if (!done[0] && answered[0] == replicas.size()) {
					done[0] = true;
					ops.ack(req, false);
				}
			});
	}

	@Override
	public void onRead(NodeRef at, ReadRequest req, StorageOps ops) {
		List<NodeRef> replicas = Replicas.forKey(ops, req.key(), n);
		int needed = Math.min(r, replicas.size());
		List<NodeRef> from = new ArrayList<>();
		List<VersionedValue> values = new ArrayList<>();
		boolean[] done = { false };
		for (NodeRef rep : replicas)
			ctx.request(at, rep, 64, new Get(req.key()), req.deadline() - ctx.now(), reply -> {
				@SuppressWarnings("unchecked")
				Optional<VersionedValue> v = (Optional<VersionedValue>) reply.payload();
				from.add(reply.from());
				values.add(v.orElse(null));
				if (done[0] || from.size() < needed)
					return;
				done[0] = true;
				VersionedValue newest = null;
				for (VersionedValue x : values)
					newest = VersionedValue.lww(newest, x);
				ops.reply(req, Optional.ofNullable(newest));
				// read repair
				for (int i = 0; i < from.size(); i++)
					if (newest != null && newest.newerThan(values.get(i)))
						ctx.send(at, from.get(i), newest.bytes() + 64, new Put(newest));
			}, () -> {
				// a missing replica: the read may still reach r answers
			});
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
		return super.onRequest(at, from, request);
	}

	@Override
	public void onMessage(NodeRef at, NodeRef from, Object payload) {
		if (payload instanceof Put p)
			store(at, p.value());
	}

	private void store(NodeRef at, VersionedValue v) {
		if (v.newerThan(ops.local(at, v.key()).orElse(null)))
			ops.storeLocal(at, v);
	}
}
