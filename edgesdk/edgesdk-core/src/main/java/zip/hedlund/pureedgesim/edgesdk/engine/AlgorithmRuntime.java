package zip.hedlund.pureedgesim.edgesdk.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import zip.hedlund.pureedgesim.edgesdk.algorithm.Algorithm;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Cancellable;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Declaration;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Deferred;
import zip.hedlund.pureedgesim.edgesdk.algorithm.LocalView;
import zip.hedlund.pureedgesim.edgesdk.algorithm.NodeLocal;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Reply;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Response;
import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.metrics.MetricsCollector;
import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.network.Transfer;

/**
 * The {@link SdkContext} of one algorithm. Every callback into the algorithm is
 * guarded so that it only runs at a node that is up and has not crashed since
 * the action was scheduled (its epoch is unchanged).
 */
final class AlgorithmRuntime implements SdkContext {

	private final SdkWorld world;
	private final Algorithm algorithm;
	private final Transfer.Kind kind;
	private final Random rng;
	private final List<NodeLocalImpl<?>> locals = new ArrayList<>();

	AlgorithmRuntime(SdkWorld world, Algorithm algorithm, Transfer.Kind kind, Random rng) {
		this.world = world;
		this.algorithm = algorithm;
		this.kind = kind;
		this.rng = rng;
	}

	Algorithm algorithm() {
		return algorithm;
	}

	void start() {
		algorithm.bind(this);
		algorithm.onStart(this);
	}

	void stop() {
		algorithm.onStop();
	}

	void onNodeCrashed(SdkNode node) {
		for (NodeLocalImpl<?> l : locals)
			l.values.remove(node.ref());
	}

	void onNodeRestarted(SdkNode node) {
		algorithm.onNodeRestarted(node.ref());
	}

	@Override
	public double now() {
		return world.now();
	}

	@Override
	public Random rng() {
		return rng;
	}

	@Override
	public DesignTopology topology() {
		return world.design();
	}

	@Override
	public LocalView local(NodeRef at) {
		SdkNode n = world.node(at);
		var energy = n.sdkEnergy();
		return new LocalView(at, now(), n.position(), (int) n.getNumberOfCPUCores(), n.busyCores(), n.queued(),
				energy.isBatteryPowered() ? energy.getBatteryLevelPercentage() : Double.NaN);
	}

	@Override
	public <T> NodeLocal<T> nodeLocal(Function<NodeRef, T> init) {
		NodeLocalImpl<T> l = new NodeLocalImpl<>(init);
		locals.add(l);
		return l;
	}

	@Override
	public void send(NodeRef from, NodeRef to, long bytes, Object payload) {
		SdkNode src = world.node(from);
		SdkNode dst = world.node(to);
		if (!src.isUp())
			return;
		world.network().send(src, dst, bytes, kind, t -> {
			if (dst.isUp())
				algorithm.onMessage(to, from, payload);
		}, null);
	}

	@Override
	public void request(NodeRef from, NodeRef to, long bytes, Object payload, double timeout,
			Consumer<Reply> onReply, Runnable onTimeout) {
		SdkNode src = world.node(from);
		SdkNode dst = world.node(to);
		if (!src.isUp())
			return;
		int epoch = src.epoch();
		double sentAt = now();
		boolean[] done = { false };
		world.after(timeout, () -> {
			if (!done[0] && src.isUp() && src.epoch() == epoch) {
				done[0] = true;
				onTimeout.run();
			}
		});
		world.network().send(src, dst, bytes, kind, t -> {
			if (!dst.isUp())
				return;
			Object answer = algorithm.onRequest(to, from, payload);
			if (answer == null)
				return;
			int dstEpoch = dst.epoch();
			BiConsumer<Object, Long> sendReply = (replyPayload, replyBytes) -> {
				if (!dst.isUp() || dst.epoch() != dstEpoch)
					return;
				world.network().send(dst, src, replyBytes, kind, back -> {
					if (!done[0] && src.isUp() && src.epoch() == epoch) {
						done[0] = true;
						onReply.accept(new Reply(to, replyPayload, now() - sentAt));
					}
				}, null);
			};
			if (answer instanceof Deferred d)
				d.connect(sendReply);
			else if (answer instanceof Response r)
				sendReply.accept(r.payload(), r.bytes());
			else
				sendReply.accept(answer, Response.DEFAULT_BYTES);
		}, null);
	}

	@Override
	public Cancellable schedule(NodeRef at, double delay, Runnable action) {
		SdkNode node = world.node(at);
		int epoch = node.epoch();
		return world.after(delay, () -> {
			if (node.isUp() && node.epoch() == epoch)
				action.run();
		});
	}

	@Override
	public Cancellable every(NodeRef at, double period, Runnable action) {
		if (!(period > 0))
			throw new IllegalArgumentException("period must be positive");
		SdkNode node = world.node(at);
		int epoch = node.epoch();
		Periodic p = new Periodic();
		p.next(world, node, epoch, period, action);
		return p;
	}

	@Override
	public void declare(Declaration d) {
		world.declare(d.by(), d.target(), d.suspected());
	}

	@Override
	public MetricsCollector metrics() {
		return world.custom();
	}

	private static final class Periodic implements Cancellable {
		private boolean cancelled;

		void next(SdkWorld world, SdkNode node, int epoch, double period, Runnable action) {
			world.after(period, () -> {
				if (cancelled || !node.isUp() || node.epoch() != epoch)
					return;
				action.run();
				next(world, node, epoch, period, action);
			});
		}

		@Override
		public void cancel() {
			cancelled = true;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}
	}

	private static final class NodeLocalImpl<T> implements NodeLocal<T> {
		private final Function<NodeRef, T> init;
		private final Map<NodeRef, T> values = new HashMap<>();

		NodeLocalImpl(Function<NodeRef, T> init) {
			this.init = init;
		}

		@Override
		public T at(NodeRef node) {
			return values.computeIfAbsent(node, init);
		}

		@Override
		public void set(NodeRef node, T value) {
			values.put(node, value);
		}
	}
}
