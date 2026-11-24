package zip.hedlund.pureedgesim.edgesdk.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import zip.hedlund.pureedgesim.edgesdk.metrics.StorageRecord;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.storage.ReadRequest;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageOps;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageStrategy;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageWorkload;
import zip.hedlund.pureedgesim.edgesdk.storage.VersionedValue;
import zip.hedlund.pureedgesim.edgesdk.storage.WriteRequest;

/**
 * The key-value stores of the nodes, the client operations, and the ground truth
 * used to judge consistency: which writes were acknowledged, and when.
 */
final class StorageEngine {

	private record Committed(long writeId, double writtenAt, double ackAt) {
	}

	private static final class Op {
		final WriteRequest write;
		final ReadRequest read;
		double answeredAt = Double.NaN;
		boolean ok;
		int versionsBehind;
		double staleness;

		Op(WriteRequest write, ReadRequest read) {
			this.write = write;
			this.read = read;
		}

		double issuedAt() {
			return write != null ? write.issuedAt() : read.issuedAt();
		}

		double deadline() {
			return write != null ? write.deadline() : read.deadline();
		}
	}

	private final SdkWorld world;
	private final StorageStrategy strategy;
	private final StorageWorkload workload;
	private final List<NodeRef> hosts;
	private final Map<SdkNode, Map<String, VersionedValue>> stores = new HashMap<>();
	private final Map<String, List<Committed>> committed = new HashMap<>();
	private final Map<Long, Op> writes = new HashMap<>();
	private final Map<Long, Op> reads = new HashMap<>();
	private final List<Op> ops = new ArrayList<>();
	private final Ops facade = new Ops();

	StorageEngine(SdkWorld world, AlgorithmRuntime runtime, StorageStrategy strategy, StorageWorkload workload) {
		this.world = world;
		this.strategy = strategy;
		this.workload = workload;
		this.hosts = List.copyOf(workload.hosts().resolve(world.design()));
		if (hosts.isEmpty())
			throw new IllegalArgumentException("the storage workload selects no host node");
	}

	StorageOps ops() {
		return facade;
	}

	/** Draws every operation of the run (workload seed) and schedules it. */
	void start() {
		double[] cdf = zipfCdf(workload.keys(), workload.zipfExponent());
		long id = 0;
		List<Op> drawn = new ArrayList<>();
		for (NodeRef client : workload.clients().resolve(world.design())) {
			Random r = new Random(world.spec().workloadSeed() ^ ("storage/" + client.name()).hashCode() * 0xBF58476D1CE4E5B9L);
			drawn.addAll(draw(r, client, workload.readsPerMinute(), false, cdf));
			drawn.addAll(draw(r, client, workload.writesPerMinute(), true, cdf));
		}
		drawn.sort(Comparator.comparingDouble(Op::issuedAt).thenComparing(o -> o.write != null ? 0 : 1));
		for (Op o : drawn) {
			Op numbered = o.write != null
					? new Op(new WriteRequest(id++, o.write.key(), o.write.valueBytes(), o.write.client(),
							o.write.issuedAt(), o.write.deadline()), null)
					: new Op(null, new ReadRequest(id++, o.read.key(), o.read.client(), o.read.issuedAt(),
							o.read.deadline()));
			ops.add(numbered);
			if (numbered.write != null)
				writes.put(numbered.write.id(), numbered);
			else
				reads.put(numbered.read.id(), numbered);
			world.at(numbered.issuedAt(), () -> issue(numbered));
		}
	}

	private List<Op> draw(Random r, NodeRef client, double perMinute, boolean write, double[] cdf) {
		List<Op> out = new ArrayList<>();
		if (perMinute <= 0)
			return out;
		double mean = 60.0 / perMinute;
		double t = 0;
		while (true) {
			t += -Math.log(1 - r.nextDouble()) * mean;
			if (t + workload.timeoutS() > world.duration())
				return out;
			String key = StorageWorkload.keyName(sampleKey(cdf, r.nextDouble()));
			if (write)
				out.add(new Op(new WriteRequest(0, key, workload.valueBytes(), client, t, t + workload.timeoutS()),
						null));
			else
				out.add(new Op(null, new ReadRequest(0, key, client, t, t + workload.timeoutS())));
		}
	}

	private static double[] zipfCdf(int n, double s) {
		double[] cdf = new double[n];
		double sum = 0;
		for (int i = 0; i < n; i++) {
			sum += 1 / Math.pow(i + 1, s);
			cdf[i] = sum;
		}
		for (int i = 0; i < n; i++)
			cdf[i] /= sum;
		return cdf;
	}

	private static int sampleKey(double[] cdf, double u) {
		int lo = 0;
		int hi = cdf.length - 1;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (cdf[mid] < u)
				lo = mid + 1;
			else
				hi = mid;
		}
		return lo;
	}

	private void issue(Op o) {
		NodeRef client = o.write != null ? o.write.client() : o.read.client();
		SdkNode node = world.node(client);
		if (!node.isUp())
			return; // unanswered: counted as unavailable
		if (o.write != null)
			strategy.onWrite(client, o.write, facade);
		else
			strategy.onRead(client, o.read, facade);
	}

	void onNodeCrashed(SdkNode node) {
		if (world.scenario().volatileStorage())
			stores.remove(node);
	}

	/** @return keys whose live hosts holding them disagree on the version */
	int divergentKeys() {
		Map<String, Set<Long>> versions = new HashMap<>();
		for (Map.Entry<SdkNode, Map<String, VersionedValue>> e : stores.entrySet()) {
			if (!e.getKey().isUp())
				continue;
			for (VersionedValue v : e.getValue().values())
				versions.computeIfAbsent(v.key(), k -> new HashSet<>()).add(v.writeId());
		}
		return (int) versions.values().stream().filter(s -> s.size() > 1).count();
	}

	List<StorageRecord> records() {
		List<StorageRecord> out = new ArrayList<>(ops.size());
		for (Op o : ops) {
			boolean write = o.write != null;
			out.add(new StorageRecord(write ? o.write.id() : o.read.id(), write, write ? o.write.key() : o.read.key(),
					(write ? o.write.client() : o.read.client()).name(), o.issuedAt(), o.answeredAt, o.ok,
					o.versionsBehind, o.staleness, world.faultLog().partitionActive(o.issuedAt())));
		}
		return out;
	}

	private final class Ops implements StorageOps {

		@Override
		public List<NodeRef> storageNodes() {
			return hosts;
		}

		@Override
		public void storeLocal(NodeRef node, VersionedValue value) {
			SdkNode n = world.node(node);
			if (!n.isUp())
				return;
			stores.computeIfAbsent(n, k -> new LinkedHashMap<>()).put(value.key(), value);
		}

		@Override
		public Optional<VersionedValue> local(NodeRef node, String key) {
			SdkNode n = world.node(node);
			if (!n.isUp())
				return Optional.empty();
			return Optional.ofNullable(stores.getOrDefault(n, Map.of()).get(key));
		}

		@Override
		public Set<String> keys(NodeRef node) {
			SdkNode n = world.node(node);
			if (!n.isUp())
				return Set.of();
			return new TreeSet<>(stores.getOrDefault(n, Map.of()).keySet());
		}

		@Override
		public void ack(WriteRequest write, boolean ok) {
			Op o = writes.get(write.id());
			if (o == null || !Double.isNaN(o.answeredAt) || world.now() > o.deadline())
				return;
			if (!world.node(write.client()).isUp())
				return;
			o.answeredAt = world.now();
			o.ok = ok;
			if (ok)
				committed.computeIfAbsent(write.key(), k -> new ArrayList<>())
						.add(new Committed(write.id(), write.issuedAt(), world.now()));
		}

		@Override
		public void reply(ReadRequest read, Optional<VersionedValue> value) {
			Op o = reads.get(read.id());
			if (o == null || !Double.isNaN(o.answeredAt) || world.now() > o.deadline())
				return;
			if (!world.node(read.client()).isUp())
				return;
			o.answeredAt = world.now();
			// writes acknowledged before the read was issued
			List<Committed> before = committed.getOrDefault(read.key(), List.of()).stream()
					.filter(c -> c.ackAt() <= read.issuedAt()).toList();
			if (value.isEmpty()) {
				o.ok = before.isEmpty();
				return;
			}
			VersionedValue v = value.get();
			o.ok = true;
			List<Committed> newer = before.stream().filter(
					c -> c.writtenAt() > v.writtenAt() || (c.writtenAt() == v.writtenAt() && c.writeId() > v.writeId()))
					.toList();
			o.versionsBehind = newer.size();
			o.staleness = newer.stream().mapToDouble(c -> read.issuedAt() - c.ackAt()).max().orElse(0);
		}
	}
}
