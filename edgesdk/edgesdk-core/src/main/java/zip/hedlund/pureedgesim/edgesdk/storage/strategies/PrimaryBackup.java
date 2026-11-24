package zip.hedlund.pureedgesim.edgesdk.storage.strategies;

import java.util.List;
import java.util.Optional;

import zip.hedlund.pureedgesim.edgesdk.algorithm.Deferred;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Response;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.storage.ReadRequest;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageOps;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageStrategy;
import zip.hedlund.pureedgesim.edgesdk.storage.VersionedValue;
import zip.hedlund.pureedgesim.edgesdk.storage.WriteRequest;

/**
 * Primary-backup replication (consistency over availability). Each key has a
 * fixed primary and {@code replicas - 1} backups. Clients write and read at the
 * primary only; with synchronous replication the primary acknowledges a write
 * once every backup has stored it. If the primary is unreachable, the key is
 * unavailable: there is no failover.
 */
public class PrimaryBackup extends StorageStrategy {

	record Write(VersionedValue value) {
	}

	record Replicate(VersionedValue value) {
	}

	record Read(String key) {
	}

	private final int replicas;
	private final boolean synchronous;

	/** Synchronous replication to {@code replicas - 1} backups. */
	public PrimaryBackup(int replicas) {
		this(replicas, true);
	}

	public PrimaryBackup(int replicas, boolean synchronous) {
		if (replicas < 1)
			throw new IllegalArgumentException("replicas must be at least 1");
		this.replicas = replicas;
		this.synchronous = synchronous;
	}

	@Override
	public void onWrite(NodeRef at, WriteRequest w, StorageOps ops) {
		NodeRef primary = Replicas.forKey(ops, w.key(), replicas).get(0);
		ctx.request(at, primary, w.valueBytes() + 64, new Write(w.version(
				zip.hedlund.pureedgesim.edgesdk.storage.VectorClock.EMPTY)), w.deadline() - ctx.now(),
				reply -> ops.ack(w, Boolean.TRUE.equals(reply.payload())), () -> ops.ack(w, false));
	}

	@Override
	public void onRead(NodeRef at, ReadRequest r, StorageOps ops) {
		NodeRef primary = Replicas.forKey(ops, r.key(), replicas).get(0);
		ctx.request(at, primary, 64, new Read(r.key()), r.deadline() - ctx.now(), reply -> {
			@SuppressWarnings("unchecked")
			Optional<VersionedValue> v = (Optional<VersionedValue>) reply.payload();
			ops.reply(r, v);
		}, () -> {
			// unavailable: no answer
		});
	}

	@Override
	public Object onRequest(NodeRef at, NodeRef from, Object request) {
		if (request instanceof Write w) {
			VersionedValue v = w.value().withClock(w.value().clock().increment(at.name()));
			ops.storeLocal(at, v);
			List<NodeRef> backups = Replicas.forKey(ops, v.key(), replicas);
			backups = backups.subList(1, backups.size());
			if (!synchronous || backups.isEmpty()) {
				for (NodeRef b : backups)
					ctx.send(at, b, v.bytes() + 64, new Replicate(v));
				return true;
			}
			Deferred d = new Deferred();
			int[] acks = { 0 };
			int[] answered = { 0 };
			int needed = backups.size();
			for (NodeRef b : backups)
				ctx.request(at, b, v.bytes() + 64, new Replicate(v), 1.0, reply -> {
					acks[0]++;
					answered[0]++;
					if (acks[0] == needed)
						d.reply(true);
				}, () -> {
					answered[0]++;
					if (answered[0] == needed)
						d.reply(false);
				});
			return d;
		}
		if (request instanceof Replicate rep) {
			if (rep.value().newerThan(ops.local(at, rep.value().key()).orElse(null)))
				ops.storeLocal(at, rep.value());
			return true;
		}
		if (request instanceof Read rd) {
			Optional<VersionedValue> v = ops.local(at, rd.key());
			return Response.of(v, 64 + v.map(VersionedValue::bytes).orElse(0L));
		}
		return super.onRequest(at, from, request);
	}

	@Override
	public void onMessage(NodeRef at, NodeRef from, Object payload) {
		if (payload instanceof Replicate rep && rep.value().newerThan(ops.local(at, rep.value().key()).orElse(null)))
			ops.storeLocal(at, rep.value());
	}
}
