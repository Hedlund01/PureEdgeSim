package zip.hedlund.pureedgesim.edgesdk.algorithm.policies;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.detect.HeartbeatDetector;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;

/**
 * A partition-aware offloading policy built only on what devices can observe.
 * Each device monitors its nearest servers (by design latency) with a
 * {@link HeartbeatDetector}, offloads to the first one it does not suspect, and
 * runs the task locally when it suspects them all. An attempt that gets no
 * result within a fraction of the deadline is retried on the next unsuspected
 * server, or locally.
 */
public class HeartbeatFallback extends OrchestrationPolicy {

	private final int servers;
	private final double period;
	private final double timeout;
	private final int misses;
	private final double retryFraction;
	private HeartbeatDetector detector;
	private final Map<NodeRef, List<NodeRef>> preferences = new TreeMap<>();

	/** Monitors the 3 nearest servers, probes every second, suspects after 2 misses of 0.5 s. */
	public HeartbeatFallback() {
		this(3, 1.0, 0.5, 2, 0.6);
	}

	/**
	 * @param servers       how many servers each device monitors
	 * @param period        probe period, seconds
	 * @param timeout       probe timeout, seconds
	 * @param misses        misses before suspecting
	 * @param retryFraction retry after this fraction of the task's deadline
	 */
	public HeartbeatFallback(int servers, double period, double timeout, int misses, double retryFraction) {
		this.servers = servers;
		this.period = period;
		this.timeout = timeout;
		this.misses = misses;
		this.retryFraction = retryFraction;
	}

	@Override
	public void onStart(SdkContext ctx) {
		detector = new HeartbeatDetector(ctx, period, timeout, misses);
		List<NodeRef> all = new ArrayList<>(ctx.topology().edgeNodes());
		all.addAll(ctx.topology().nodes(Tier.CLOUD));
		for (NodeRef device : ctx.topology().nodes(Tier.END_DEVICE)) {
			List<NodeRef> ordered = ctx.topology().byDesignLatency(device, all);
			preferences.put(device, ordered.subList(0, Math.min(servers, ordered.size())));
			detector.monitor(device, preferences.get(device));
		}
	}

	@Override
	public void onNodeRestarted(NodeRef at) {
		if (preferences.containsKey(at))
			detector.monitor(at, preferences.get(at));
	}

	@Override
	public Placement place(NodeRef device, TaskRequest task) {
		return choose(device, task, null);
	}

	@Override
	public Placement onAttemptTimedOut(NodeRef device, TaskRequest task, NodeRef tried, int attempt) {
		if (tried.equals(device) || attempt > servers)
			return Placement.reject();
		return choose(device, task, tried);
	}

	private Placement choose(NodeRef device, TaskRequest task, NodeRef skip) {
		double retry = (task.deadline() - task.createdAt()) * retryFraction;
		for (NodeRef s : preferences.getOrDefault(device, List.of()))
			if (!s.equals(skip) && !detector.suspects(device, s))
				return Placement.on(s).withRetryAfter(retry);
		return Placement.local();
	}

	/** @return the detector, e.g. for tests */
	public HeartbeatDetector detector() {
		return detector;
	}
}
