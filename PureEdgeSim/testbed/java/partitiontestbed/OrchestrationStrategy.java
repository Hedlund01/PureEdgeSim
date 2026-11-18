package partitiontestbed;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

/**
 * An orchestration algorithm under test: the one thing that differs between two
 * runs of the testbed.
 * <p>
 * The testbed builds the continuum, makes the devices create tasks, breaks the
 * network and judges every task against the requirement of its service. It
 * decides nothing about where a task runs, how often, or what happens when
 * something breaks: the strategy does, at each point where a real system would
 * decide, through these hooks:
 * <ol>
 * <li>{@link #onTaskCreated}: on the device, a task is created. By default it is
 * sent to the access server the device is attached to, and lost when there is
 * none; a strategy can buffer it, resend it, or send it to several servers.</li>
 * <li>{@link #onTaskArrived}: a task reached a server, the access server of its
 * device or, in an edge pipeline, the host of the stage before it. The strategy
 * dispatches it to one host or several ({@link StrategyContext#dispatch}), now or
 * later, after deploying an instance or asking other nodes.</li>
 * <li>{@link #onNoInstance}: an attempt reached a host with no instance of its
 * service.</li>
 * <li>{@link #onExecuted}: an attempt ran. By default its result is sent to the
 * device, or the task ends there when it returns nothing; a strategy can hold
 * the result and send it again later.</li>
 * <li>{@link #onResultReceived}: on the device, a result arrived.</li>
 * <li>{@link #onMessage} and {@link #onRpc}: the strategy's own protocol, over
 * the same network: pings, gossip, heartbeats, deployment requests.</li>
 * <li>{@link #onAttachmentChanged}, {@link #admitJoin} and
 * {@link #onNodeRestarted}: the device's radio, the partition groups and crashed
 * servers.</li>
 * </ol>
 * How the strategy's traffic moves is part of its design too: {@link #network()}
 * says which links it uses (within a layer, between layers), which nodes relay,
 * and whether radio contacts may bridge partition groups. The testbed only
 * decides which links exist and when they break, the same for every strategy.
 * What the strategy can see and do is {@link #ctx}: no global knowledge of the
 * network, only configuration, what is local to a node, and its own messages.
 * Retries, holding results, backup hosts, parallel copies of a task and failure
 * detection are therefore all the strategy's, and cost what they cost on the
 * network and on the hosts.
 * <p>
 * A strategy is registered under one or more algorithm names with
 * {@link Testbed#register(String, java.util.function.Supplier)}, and selected by
 * {@code orchestration_algorithms}, so one run can compare several of them on the
 * same continuum, failures, partitions and tasks.
 *
 * @see StrategyContext
 * @see partitiontestbed.baselines
 * @see partitiontestbed.flocky.FlockyStrategy
 */
public abstract class OrchestrationStrategy {

	/** What the strategy sees and does; set before {@link #initialize()}. */
	protected StrategyContext ctx;

	void bind(StrategyContext context) {
		this.ctx = context;
	}

	/**
	 * Called once the continuum is built and before the first event: the place to
	 * read its configuration, create its state for every node, start its periodic
	 * work with {@link StrategyContext#schedule} and start instances it wants from
	 * the beginning.
	 */
	public abstract void initialize();

	/**
	 * Says how the strategy uses the network. Read once, when the topology is
	 * built, before {@link #initialize()}: the strategy's {@link #ctx} is not set
	 * yet.
	 *
	 * @return the policy; by default {@link NetworkPolicy#hierarchical()}
	 */
	public NetworkPolicy network() {
		return NetworkPolicy.hierarchical();
	}

	/**
	 * A device created a task. Runs on the device.
	 *
	 * @param device the device
	 * @param job    the task
	 */
	public void onTaskCreated(ComputingNode device, Job job) {
		EdgeServer attachment = ctx.attachment(device);
		if (attachment != null)
			ctx.sendRequest(job, device, attachment.node);
	}

	/**
	 * A task reached a server: the access server it was sent to, or the host of
	 * the stage before it in an edge pipeline. Runs on that server.
	 *
	 * @param node the server
	 * @param job  the task
	 */
	public abstract void onTaskArrived(ComputingNode node, Job job);

	/**
	 * An attempt reached a host that runs no instance of its service. Runs on the
	 * host. By default the attempt is lost; a strategy can start an instance and
	 * dispatch it again, or send it elsewhere.
	 *
	 * @param host    the host
	 * @param attempt the attempt, already marked lost
	 */
	public void onNoInstance(ComputingNode host, Attempt attempt) {
	}

	/**
	 * An attempt ran. Runs on its host. By default the result goes to the device,
	 * or the task ends here when it returns nothing to the device.
	 *
	 * @param host    the host
	 * @param attempt the attempt
	 */
	public void onExecuted(ComputingNode host, Attempt attempt) {
		if (attempt.job().returnsResult())
			ctx.sendResult(attempt, host);
		else
			ctx.finishAtHost(attempt);
	}

	/**
	 * A result reached the device. Runs on the device.
	 *
	 * @param device  the device
	 * @param attempt the attempt whose result it is
	 * @param first   whether it is the first result of its task, the one that
	 *                counts, or of its part for a divided task
	 */
	public void onResultReceived(ComputingNode device, Attempt attempt, boolean first) {
	}

	/**
	 * A message of the strategy arrived.
	 *
	 * @param node    the node it arrived at
	 * @param from    the node that sent it
	 * @param payload what was sent
	 */
	public void onMessage(ComputingNode node, ComputingNode from, Object payload) {
	}

	/**
	 * A node is called through {@link StrategyContext#rpc}.
	 *
	 * @param node    the node called
	 * @param from    the caller
	 * @param request what it asks
	 * @return the reply, or {@code null} to send none, so that the caller times out
	 */
	public StrategyContext.Reply onRpc(ComputingNode node, ComputingNode from, Object request) {
		return null;
	}

	/**
	 * The radio of a device changed: it attached to another access server
	 * ({@code from} and {@code to} both set), lost every server in range
	 * ({@code to} is {@code null}), or found one again ({@code from} is
	 * {@code null}). Runs on the device.
	 *
	 * @param device the device
	 * @param from   the access server it had, or {@code null}
	 * @param to     the access server it has now, or {@code null}
	 */
	public void onAttachmentChanged(ComputingNode device, EdgeServer from, EdgeServer to) {
	}

	/**
	 * Asks whether the group of {@code contact} admits a node that a partition in
	 * progress put in another group, and that can now reach {@code contact}:
	 * <ul>
	 * <li>a <b>moving edge server</b> that lost contact with every server of its own
	 * group and came within mesh range of {@code contact}. Admitted, it becomes a
	 * member of that group: its mesh links follow the group, and so does its cloud
	 * uplink when it is a gateway;</li>
	 * <li>a <b>device</b> that has to leave its access server and whose nearest access
	 * server in range, {@code contact}, is in another group. Admitted, it attaches
	 * to it; refused, it tries the next one in range, and is cut off when none
	 * admits it.</li>
	 * </ul>
	 * The testbed asks every second for as long as the situation lasts, and only
	 * when {@code partitions.group_transfer} is on.
	 *
	 * @param node    the server or the device asking to join
	 * @param contact the server of the other group it can reach
	 * @return whether it joins; the default admits every node
	 */
	public boolean admitJoin(ComputingNode node, EdgeServer contact) {
		return true;
	}

	/**
	 * A server that crashed ({@code failures.node_failure_mode=crash}) runs again,
	 * with no instance and nothing of what ran on it. Runs on the server: the place
	 * to reset what the strategy kept there.
	 *
	 * @param node the server
	 */
	public void onNodeRestarted(ComputingNode node) {
	}

	// --------------------------------------------------------------- output

	/** @return the names of the columns the strategy adds to the timeline file */
	public List<String> timelineColumns() {
		return List.of();
	}

	/**
	 * Called once per row of the timeline.
	 *
	 * @return the values of the strategy's columns, in the order of
	 *         {@link #timelineColumns()}
	 */
	public List<String> timelineValues() {
		return List.of();
	}

	/** @return lines the strategy adds to the report at the end of the run */
	public List<String> reportLines() {
		return List.of();
	}

	/**
	 * Writes files of the strategy's own at the end of the run.
	 *
	 * @param folder the output folder
	 * @param label  what names the files of this run
	 * @throws IOException when a file cannot be written
	 */
	public void write(Path folder, String label) throws IOException {
	}
}
