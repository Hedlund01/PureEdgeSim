package partitiontestbed.baselines;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

import partitiontestbed.Attempt;
import partitiontestbed.EdgeServer;
import partitiontestbed.Job;
import partitiontestbed.Testbed;

/**
 * {@link AccessServerStrategy} made reliable with acknowledgements, as a message
 * queue with at-least-once delivery would:
 * <ul>
 * <li>the <b>device</b> keeps every task until it is acknowledged, sends it to its
 * access server, again every {@code baselines.retry.interval_s}, and at once when
 * it finds a server again after it was out of range;</li>
 * <li>a <b>host</b> holds every result until the device acknowledges it, and sends
 * it again at the same interval, to wherever the device is attached by then; a
 * task that returns nothing is acknowledged by its host once it ran;</li>
 * <li>a host that gets a task again sends the result it holds instead of running
 * it twice.</li>
 * </ul>
 * A sender gives up once retrying can no longer help: at the deadline of a
 * {@code deadline} task, at the end of the delivery window of a {@code lossless}
 * one. Later stages of an edge pipeline are not retried by the device, which
 * only sees the first stage and the last result.
 */
public class RetryStrategy extends AccessServerStrategy {

	/** The name it is registered under. */
	public static final String NAME = "RETRY";

	/** Size of an acknowledgement, in bytes. */
	private static final long ACK_BYTES = 64;

	/** An acknowledgement of a task or a result. */
	private static final class Ack {
		final int job;

		Ack(int job) {
			this.job = job;
		}
	}

	private BaselineConfig config;
	/** On each device, its tasks not acknowledged yet. */
	private final Map<ComputingNode, Map<Integer, Job>> pending = new HashMap<>();
	/** On each host, the results it holds until their device acknowledges them. */
	private final Map<ComputingNode, Map<Integer, Attempt>> held = new HashMap<>();
	/** On each host, the tasks running there, so that a task sent again does not run twice. */
	private final Map<ComputingNode, Set<Integer>> running = new HashMap<>();
	private int requestsSent;
	private int resultsSent;

	@Override
	public void initialize() {
		config = new BaselineConfig(Testbed.settingsFile("baselines.properties"));
	}

	private static double giveUp(Job job) {
		return job.createdAt() + (job.service().lossless ? Math.max(job.service().deliveryWindow, job.deadline())
				: job.deadline());
	}

	// --------------------------------------------------------------- device

	@Override
	public void onTaskCreated(ComputingNode device, Job job) {
		pending.computeIfAbsent(device, key -> new LinkedHashMap<>()).put(job.id(), job);
		transmit(device, job);
		ctx.schedule(config.retryInterval, () -> retry(device, job));
	}

	private void transmit(ComputingNode device, Job job) {
		EdgeServer attachment = ctx.attachment(device);
		if (attachment == null)
			return;
		requestsSent++;
		ctx.sendRequest(job, device, attachment.node);
	}

	private void retry(ComputingNode device, Job job) {
		Map<Integer, Job> mine = pending.get(device);
		if (mine == null || !mine.containsKey(job.id()))
			return;
		if (ctx.now() > giveUp(job)) {
			mine.remove(job.id());
			return;
		}
		transmit(device, job);
		ctx.schedule(config.retryInterval, () -> retry(device, job));
	}

	@Override
	public void onAttachmentChanged(ComputingNode device, EdgeServer from, EdgeServer to) {
		Map<Integer, Job> mine = pending.get(device);
		if (to == null || mine == null)
			return;
		for (Job job : new ArrayList<>(mine.values()))
			transmit(device, job);
	}

	@Override
	public void onResultReceived(ComputingNode device, Attempt attempt, boolean first) {
		Map<Integer, Job> mine = pending.get(device);
		if (mine != null)
			mine.remove(attempt.job().id());
		ctx.send(device, attempt.host(), ACK_BYTES, new Ack(attempt.job().id()));
	}

	// ----------------------------------------------------------------- host

	@Override
	public void onTaskArrived(ComputingNode node, Job job) {
		Attempt result = held.getOrDefault(node, Map.of()).get(job.id());
		if (result != null) {
			resultsSent++;
			ctx.sendResult(result, node);
			return;
		}
		Set<Integer> here = running.computeIfAbsent(node, key -> new HashSet<>());
		if (!here.add(job.id()))
			return;
		if (!runHere(node, job))
			here.remove(job.id());
	}

	@Override
	public void onExecuted(ComputingNode host, Attempt attempt) {
		Job job = attempt.job();
		Set<Integer> here = running.get(host);
		if (here != null)
			here.remove(job.id());
		if (!job.returnsResult()) {
			ctx.finishAtHost(attempt);
			ctx.send(host, job.device(), ACK_BYTES, new Ack(job.id()));
			return;
		}
		held.computeIfAbsent(host, key -> new HashMap<>()).put(job.id(), attempt);
		resultsSent++;
		ctx.sendResult(attempt, host);
		ctx.schedule(config.retryInterval, () -> resendResult(host, attempt));
	}

	private void resendResult(ComputingNode host, Attempt attempt) {
		Map<Integer, Attempt> results = held.get(host);
		if (results == null || results.get(attempt.job().id()) != attempt)
			return;
		if (ctx.now() > giveUp(attempt.job()) || !ctx.isUp(host)) {
			results.remove(attempt.job().id());
			return;
		}
		resultsSent++;
		ctx.sendResult(attempt, host);
		ctx.schedule(config.retryInterval, () -> resendResult(host, attempt));
	}

	@Override
	public void onMessage(ComputingNode node, ComputingNode from, Object payload) {
		if (!(payload instanceof Ack))
			return;
		int job = ((Ack) payload).job;
		Map<Integer, Attempt> results = held.get(node);
		if (results != null)
			results.remove(job);
		Map<Integer, Job> mine = pending.get(node);
		if (mine != null)
			mine.remove(job);
	}

	@Override
	public void onNodeRestarted(ComputingNode node) {
		held.remove(node);
		running.remove(node);
	}

	@Override
	public List<String> reportLines() {
		return List.of(String.format(Locale.ROOT, "Retry: %d requests sent by the devices, %d results sent by the "
				+ "hosts, every %.1f s until acknowledged", requestsSent, resultsSent, config.retryInterval));
	}
}
