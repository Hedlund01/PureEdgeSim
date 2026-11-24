package zip.hedlund.pureedgesim.edgesdk.examples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import zip.hedlund.pureedgesim.edgesdk.algorithm.NodeLocal;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Response;
import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.Experiment;
import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.CloudPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.EdgePresets;
import zip.hedlund.pureedgesim.edgesdk.storage.ReadRequest;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageOps;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageStrategy;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageWorkload;
import zip.hedlund.pureedgesim.edgesdk.storage.VectorClock;
import zip.hedlund.pureedgesim.edgesdk.storage.VersionedValue;
import zip.hedlund.pureedgesim.edgesdk.storage.WriteRequest;
import zip.hedlund.pureedgesim.edgesdk.storage.strategies.EventualGossip;
import zip.hedlund.pureedgesim.edgesdk.storage.strategies.Quorum;

/**
 * User story 2: implement a distributed storage strategy and test it under a
 * partition, against two built-in strategies.
 * <p>
 * {@code PushWithRetry} is an edge-caching, eventually consistent strategy: a
 * client writes to its nearest edge server, which stores the value, answers at
 * once, and pushes the update to every other host. Pushes that are not
 * acknowledged stay in a per-node outbox (lost if the node crashes) and are
 * retried every second, so replicas converge once the partition heals.
 */
public final class Story2ReplicationUnderPartition {

	/** The custom strategy. */
	public static class PushWithRetry extends StorageStrategy {

		record Put(VersionedValue value) {
		}

		record Push(VersionedValue value) {
		}

		record Get(String key) {
		}

		private NodeLocal<Map<String, List<NodeRef>>> outbox;

		@Override
		public void onStart(SdkContext ctx) {
			outbox = ctx.nodeLocal(n -> new LinkedHashMap<>());
			for (NodeRef host : ops.storageNodes())
				ctx.every(host, 1.0, () -> flush(host));
		}

		@Override
		public void onNodeRestarted(NodeRef at) {
			if (ops.storageNodes().contains(at))
				ctx.every(at, 1.0, () -> flush(at));
		}

		@Override
		public void onWrite(NodeRef at, WriteRequest w, StorageOps ops) {
			NodeRef edge = nearest(at);
			VersionedValue v = w.version(VectorClock.EMPTY.increment(at.name()));
			ctx.request(at, edge, v.bytes() + 64, new Put(v), w.deadline() - ctx.now(), r -> ops.ack(w, true),
					() -> ops.ack(w, false));
		}

		@Override
		public void onRead(NodeRef at, ReadRequest r, StorageOps ops) {
			ctx.request(at, nearest(at), 64, new Get(r.key()), r.deadline() - ctx.now(), reply -> {
				@SuppressWarnings("unchecked")
				Optional<VersionedValue> v = (Optional<VersionedValue>) reply.payload();
				ops.reply(r, v);
			}, () -> {
			});
		}

		private NodeRef nearest(NodeRef client) {
			return ctx.topology().nearest(client, ops.storageNodes()).orElseThrow();
		}

		@Override
		public Object onRequest(NodeRef at, NodeRef from, Object request) {
			if (request instanceof Put p) {
				if (store(at, p.value())) {
					List<NodeRef> peers = new ArrayList<>(ops.storageNodes());
					peers.remove(at);
					outbox.at(at).put(p.value().key(), peers);
					flush(at);
				}
				return true;
			}
			if (request instanceof Push p) {
				store(at, p.value());
				return true;
			}
			if (request instanceof Get g) {
				Optional<VersionedValue> v = ops.local(at, g.key());
				return Response.of(v, 64 + v.map(VersionedValue::bytes).orElse(0L));
			}
			return super.onRequest(at, from, request);
		}

		/** Sends every pending push; acknowledged ones leave the outbox. */
		private void flush(NodeRef at) {
			Map<String, List<NodeRef>> pending = outbox.at(at);
			for (String key : List.copyOf(pending.keySet())) {
				VersionedValue v = ops.local(at, key).orElse(null);
				if (v == null) {
					pending.remove(key);
					continue;
				}
				for (NodeRef peer : List.copyOf(pending.get(key)))
					ctx.request(at, peer, v.bytes() + 64, new Push(v), 0.8, reply -> {
						List<NodeRef> left = pending.get(key);
						if (left != null && ops.local(at, key).map(x -> x.writeId() == v.writeId()).orElse(false)) {
							left.remove(peer);
							if (left.isEmpty())
								pending.remove(key);
						}
					}, () -> ctx.metrics().add("push_retries", 1));
			}
		}

		private boolean store(NodeRef at, VersionedValue v) {
			if (!v.newerThan(ops.local(at, v.key()).orElse(null)))
				return false;
			ops.storeLocal(at, v);
			return true;
		}
	}

	public static void main(String[] args) {
		var phone = DevicePresets.SMARTPHONE.withMobility(MobilitySpec.STATIC);
		var builder = Scenario.builder("story2-replication").duration(Examples.duration(300)).seed(21)
				.map(3_000, 1_000)
				.addEdgeNode("edge-west", EdgePresets.ROADSIDE_SERVER, Layout.at(500, 500))
				.addEdgeNode("edge-east", EdgePresets.ROADSIDE_SERVER, Layout.at(2_500, 500))
				.addCloud("cloud", CloudPresets.REGIONAL_DC, Layout.at(1_500, 900));
		for (int i = 0; i < 6; i++) {
			builder.addEndDevice("west-" + i, phone, Layout.near("edge-west", 80));
			builder.addEndDevice("east-" + i, phone, Layout.near("edge-east", 80));
		}
		Scenario scenario = builder
				.connect(Select.name("west-*"), Select.name("edge-west"), ChannelPresets.WIFI)
				.connect(Select.name("east-*"), Select.name("edge-east"), ChannelPresets.WIFI)
				.connect(Select.tier(Tier.EDGE_STATIC), Select.name("cloud"), ChannelPresets.FIBER)
				.connect("edge-west", "edge-east", ChannelPresets.FIBER)
				.group("west", Select.names("edge-west", "west-*"))
				.withStorageWorkload(StorageWorkload.zipf(100, 0.9).reads(30).writes(10).timeout(1))
				// the west region is cut off from the cloud and the east for 90 s
				.withFaults(FaultScript.parse("100s partition west for 90s")).build();

		Experiment.on(scenario)
				.candidate(Candidate.storageOnly("quorum-3-2-2", () -> new Quorum(3, 2, 2)))
				.candidate(Candidate.storageOnly("gossip", EventualGossip::new))
				.candidate(Candidate.storageOnly("push-retry", PushWithRetry::new)).baseline("quorum-3-2-2")
				.replications(Examples.replications(3))
				.metrics(Metrics.STORAGE_AVAILABILITY, Metrics.STORAGE_AVAILABILITY_PARTITION, Metrics.STALE_READS,
						Metrics.STALENESS, Metrics.DIVERGENCE, Metrics.STORAGE_CONVERGENCE, Metrics.CONTROL_OVERHEAD)
				.run(Examples.output("story2"));
	}
}
