package zip.hedlund.pureedgesim.edgesdk.storage.strategies;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import zip.hedlund.pureedgesim.edgesdk.algorithm.Deferred;
import zip.hedlund.pureedgesim.edgesdk.algorithm.NodeLocal;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Response;
import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.storage.ReadRequest;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageOps;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageStrategy;
import zip.hedlund.pureedgesim.edgesdk.storage.VectorClock;
import zip.hedlund.pureedgesim.edgesdk.storage.VersionedValue;
import zip.hedlund.pureedgesim.edgesdk.storage.WriteRequest;

/**
 * Edge caching in front of a cloud origin. Writes go to the origin (the storage
 * host with the CLOUD tier, or the first host). Reads go to the client's nearest
 * edge host, which answers from its cache while the entry is younger than
 * {@code ttl}, and otherwise fetches from the origin. When the origin cannot be
 * reached, a stale cached entry is served (availability over freshness). The
 * cache is volatile: a crash empties it.
 */
public class EdgeCache extends StorageStrategy {

	record Put(VersionedValue value) {
	}

	record Get(String key) {
	}

	record CachedGet(String key) {
	}

	private record Entry(Optional<VersionedValue> value, double fetchedAt) {
	}

	private final double ttl;
	private final double originTimeout;
	private NodeRef origin;
	private NodeLocal<Map<String, Entry>> cache;

	public EdgeCache(double ttlSeconds) {
		this(ttlSeconds, 0.5);
	}

	public EdgeCache(double ttlSeconds, double originTimeout) {
		this.ttl = ttlSeconds;
		this.originTimeout = originTimeout;
	}

	@Override
	public void onStart(SdkContext ctx) {
		List<NodeRef> hosts = ops.storageNodes();
		origin = hosts.stream().filter(h -> ctx.topology().node(h).tier() == Tier.CLOUD).findFirst()
				.orElse(hosts.get(0));
		cache = ctx.nodeLocal(n -> new HashMap<>());
	}

	@Override
	public void onWrite(NodeRef at, WriteRequest w, StorageOps ops) {
		VersionedValue v = w.version(VectorClock.EMPTY.increment(at.name()));
		ctx.request(at, origin, v.bytes() + 64, new Put(v), w.deadline() - ctx.now(), reply -> ops.ack(w, true),
				() -> ops.ack(w, false));
	}

	@Override
	public void onRead(NodeRef at, ReadRequest r, StorageOps ops) {
		List<NodeRef> edges = ops.storageNodes().stream().filter(h -> !h.equals(origin)).toList();
		NodeRef target = ctx.topology().nearest(at, edges).orElse(origin);
		Object req = target.equals(origin) ? new Get(r.key()) : new CachedGet(r.key());
		ctx.request(at, target, 64, req, r.deadline() - ctx.now(), reply -> {
			@SuppressWarnings("unchecked")
			Optional<VersionedValue> v = (Optional<VersionedValue>) reply.payload();
			ops.reply(r, v);
		}, () -> {
			// unavailable
		});
	}

	@Override
	public Object onRequest(NodeRef at, NodeRef from, Object request) {
		if (request instanceof Put p) {
			if (p.value().newerThan(ops.local(at, p.value().key()).orElse(null)))
				ops.storeLocal(at, p.value());
			return true;
		}
		if (request instanceof Get g)
			return answer(ops.local(at, g.key()));
		if (request instanceof CachedGet g) {
			Map<String, Entry> c = cache.at(at);
			Entry e = c.get(g.key());
			if (e != null && ctx.now() - e.fetchedAt() <= ttl)
				return answer(e.value());
			Deferred d = new Deferred();
			ctx.request(at, origin, 64, new Get(g.key()), originTimeout, reply -> {
				@SuppressWarnings("unchecked")
				Optional<VersionedValue> v = (Optional<VersionedValue>) reply.payload();
				c.put(g.key(), new Entry(v, ctx.now()));
				d.reply(v, size(v));
			}, () -> {
				if (e != null)
					d.reply(e.value(), size(e.value()));
			});
			return d;
		}
		return super.onRequest(at, from, request);
	}

	private static Response answer(Optional<VersionedValue> v) {
		return Response.of(v, size(v));
	}

	private static long size(Optional<VersionedValue> v) {
		return 64 + v.map(VersionedValue::bytes).orElse(0L);
	}
}
