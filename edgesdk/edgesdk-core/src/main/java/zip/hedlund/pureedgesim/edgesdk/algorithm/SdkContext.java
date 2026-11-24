package zip.hedlund.pureedgesim.edgesdk.algorithm;

import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Function;

import zip.hedlund.pureedgesim.edgesdk.metrics.MetricsCollector;
import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * What an algorithm can see and do. It deliberately exposes nothing about the
 * live state of the network: no link state, no reachability, no partitions, no
 * crash notifications, and no feedback on lost messages.
 */
public interface SdkContext {

	/** @return the simulation clock, seconds */
	double now();

	/**
	 * @return this algorithm's own random stream: seeded per run, independent of
	 *         the workload and fault streams
	 */
	Random rng();

	/** @return the topology as designed (never changes during the run) */
	DesignTopology topology();

	/** @return what node {@code at} knows about itself right now */
	LocalView local(NodeRef at);

	/**
	 * Per-node state that a crash wipes, as a reboot would.
	 *
	 * @param init creates the initial value of a node, lazily
	 */
	<T> NodeLocal<T> nodeLocal(Function<NodeRef, T> init);

	/**
	 * Sends a message over the network, fire-and-forget. It may be delayed by
	 * congestion or lost (no route, a link failing under it, packet loss, the
	 * receiver crashed); the sender is never told.
	 *
	 * @param bytes size on the wire, which competes with tasks for bandwidth
	 */
	void send(NodeRef from, NodeRef to, long bytes, Object payload);

	/**
	 * Sends a request and waits for the reply. Exactly one of the callbacks runs,
	 * at {@code from}: {@code onReply} when the reply arrives within
	 * {@code timeout} seconds, {@code onTimeout} otherwise. Neither runs if
	 * {@code from} crashes in between.
	 */
	void request(NodeRef from, NodeRef to, long bytes, Object payload, double timeout, Consumer<Reply> onReply,
			Runnable onTimeout);

	/**
	 * Runs an action at a node after a delay, unless the node crashes first.
	 *
	 * @return a handle to cancel it
	 */
	Cancellable schedule(NodeRef at, double delay, Runnable action);

	/** Runs an action at a node every {@code period} seconds, until cancelled or the node crashes. */
	Cancellable every(NodeRef at, double period, Runnable action);

	/**
	 * Declares what the algorithm believes, e.g. that it suspects a node. This
	 * changes nothing in the simulation; the SDK compares declarations with the
	 * ground truth to measure detection latency and false positives.
	 */
	void declare(Declaration declaration);

	/** @return custom counters reported with the run's metrics */
	MetricsCollector metrics();
}
