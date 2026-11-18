package partitiontestbed;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

/**
 * Everything an {@link OrchestrationStrategy} may see and do.
 * <p>
 * A strategy learns about the network the way a deployed system would: from the
 * configuration (which servers exist, their roles, traits and capacities, the
 * mesh links each server was given), from what is local to a node (whether a
 * device is attached right now, whether a node is up), and from its own messages
 * and timeouts. It cannot ask who can reach whom, at what latency, or where
 * anything is placed: there is no oracle. Its messages, its tasks and their
 * results all travel on the simulated network, share its bandwidth, take time,
 * and are lost silently when the path breaks.
 * <p>
 * The methods that act on a node ({@link #send}, {@link #dispatch},
 * {@link #startInstance}, ...) are meant to be called by the strategy's code
 * running on that node: a node can start an instance on itself, and asks another
 * one to by sending it a message. The testbed does not check who calls, so this is
 * the strategy's contract, and what keeps two strategies comparable.
 *
 * @see OrchestrationStrategy
 */
public interface StrategyContext {

	/** A reply to an RPC: what goes back, and its size on the network. */
	final class Reply {
		/** What the caller receives. */
		public final Object payload;
		/** Its size, in bytes. */
		public final long bytes;

		/**
		 * @param payload what the caller receives
		 * @param bytes   its size on the network, in bytes
		 */
		public Reply(Object payload, long bytes) {
			this.payload = payload;
			this.bytes = bytes;
		}
	}

	// ----------------------------------------------------------------- time

	/** @return the current simulation time, in seconds */
	double now();

	/**
	 * Runs an action later, as a timer of the node that set it would.
	 *
	 * @param delay  in seconds, at least 0
	 * @param action what to run
	 */
	void schedule(double delay, Runnable action);

	/**
	 * @return a random source of the strategy's own, seeded by the replication, so
	 *         that what the strategy draws changes nothing the testbed draws
	 */
	Random random();

	// ------------------------------------------------------------ the layout

	/** @return the edge servers, {@code dc1} to {@code dcN} */
	List<EdgeServer> servers();

	/** @return the access servers, which devices attach to */
	List<EdgeServer> accessServers();

	/**
	 * @param node a computing node
	 * @return its edge server, or {@code null} for the cloud or a device
	 */
	EdgeServer serverOf(ComputingNode node);

	/** @return the cloud data center */
	ComputingNode cloud();

	/** @return the traits of the cloud */
	Set<Trait> cloudTraits();

	/**
	 * @param server an edge server
	 * @return the servers it has a mesh link to, as configured: whether a link is
	 *         up, or in range for a server that moves, is not said
	 */
	List<EdgeServer> meshNeighbours(EdgeServer server);

	/** @return the services, in the order of {@code applications.xml} */
	List<Service> services();

	/**
	 * @param device an end device
	 * @return the name of its type, for example {@code UAV}
	 */
	String deviceType(ComputingNode device);

	// ------------------------------------------------------- what is local

	/**
	 * What a device knows about its own radio.
	 *
	 * @param device an end device
	 * @return the access server it is attached to and can reach right now, or
	 *         {@code null} when it is out of range of every server that answers
	 */
	EdgeServer attachment(ComputingNode device);

	/**
	 * What a node's radio hears: the nodes it has a working radio link to right
	 * now that its {@link NetworkPolicy} uses, between two devices, between a device
	 * and an access server, or between a moving edge server and another server.
	 * The wired mesh is not said, as in {@link #meshNeighbours}.
	 *
	 * @param node a device or an edge server
	 * @return its radio contacts
	 */
	List<ComputingNode> radioContacts(ComputingNode node);

	/**
	 * Makes a node forward traffic for others, or stops it, whatever the
	 * {@link NetworkPolicy} says of its layer: for example to elect a device as the
	 * relay of its neighbours. Code running on that node calls it; it changes the
	 * routes of whatever is sent from then on.
	 *
	 * @param node     the node
	 * @param relaying whether paths may go through it
	 */
	void setRelay(ComputingNode node, boolean relaying);

	/**
	 * What a node knows about itself.
	 *
	 * @param node a node
	 * @return whether it runs: {@code false} while a crashed server is down
	 */
	boolean isUp(ComputingNode node);

	// ------------------------------------------------------------ messages

	/**
	 * Sends a message, routed on the network as it is now. It arrives through
	 * {@link OrchestrationStrategy#onMessage} after its transfer time, or never,
	 * without notice, when there is no path or the path breaks on the way.
	 *
	 * @param from    the node that sends it
	 * @param to      the node it is for
	 * @param bytes   its size
	 * @param payload what the receiver gets
	 */
	void send(ComputingNode from, ComputingNode to, long bytes, Object payload);

	/**
	 * Calls a node: the request is delivered to
	 * {@link OrchestrationStrategy#onRpc}, whose reply travels back. Exactly one
	 * of {@code onReply} and {@code onTimeout} runs.
	 *
	 * @param from      the caller
	 * @param to        the node called
	 * @param bytes     the size of the request
	 * @param request   what the node called receives
	 * @param timeout   how long the caller waits for the reply, in seconds
	 * @param onReply   gets the payload of the reply
	 * @param onTimeout runs when no reply came in time
	 */
	void rpc(ComputingNode from, ComputingNode to, long bytes, Object request, double timeout,
			Consumer<Object> onReply, Runnable onTimeout);

	// --------------------------------------------------------------- tasks

	/**
	 * Sends a task from its device to a server, which gets it through
	 * {@link OrchestrationStrategy#onTaskArrived}.
	 *
	 * @param job    the task
	 * @param device the device that sends it
	 * @param server the server it goes to: usually the device's attachment
	 */
	void sendRequest(Job job, ComputingNode device, ComputingNode server);

	/**
	 * Sends a task to a host to be run there: one attempt. Calling it several times
	 * runs the task several times, in parallel or one after another; the first
	 * result to reach the device counts, the others are duplicates.
	 * <p>
	 * The host can be a device with a processor, its own device for local
	 * computing: the attempt then crosses no network when it is the device that
	 * dispatches it, and runs on a core of the device like on a server, in an
	 * instance of its service.
	 *
	 * @param job  the task
	 * @param from the node that holds it now
	 * @param host the node that runs it: an edge server, the cloud or a device
	 * @return the attempt
	 */
	Attempt dispatch(Job job, ComputingNode from, ComputingNode host);

	/**
	 * Divides a task into independent parts that can run on several hosts at once,
	 * for partial offloading: each part holds its share of the length, the input
	 * and the result of the task, and the task is done when every part is, its
	 * result back at the device or, for a task that returns nothing, finished at
	 * its host. A task is divided once, before any part is dispatched, and a stage
	 * that hands its output to the next stage on the edge cannot be, since its
	 * output has to be in one place.
	 *
	 * @param job    the task
	 * @param shares the share of each part, positive; they are scaled to add up to
	 *               one
	 * @throws IllegalStateException    when the task is already divided or hands
	 *                                  over on the edge
	 * @throws IllegalArgumentException when a share is not positive
	 */
	void divide(Job job, double... shares);

	/**
	 * Sends a part of a divided task to a host to be run there: one attempt, as
	 * {@link #dispatch} for the whole task. Sending a part again runs it again; the
	 * first of its results counts.
	 *
	 * @param job  a divided task
	 * @param part the part, from 0
	 * @param from the node that holds it now
	 * @param host the node that runs it
	 * @return the attempt
	 */
	Attempt dispatchPart(Job job, int part, ComputingNode from, ComputingNode host);

	/**
	 * Sends the result of an attempt to its device, routed to wherever the device
	 * is attached when it is sent. Only for a task that returns a result.
	 *
	 * @param attempt an attempt that ran
	 * @param from    the node that holds the result: its host, or one it was passed
	 *                to
	 */
	void sendResult(Attempt attempt, ComputingNode from);

	/**
	 * Ends a task where it ran: for a task that returns nothing to its device
	 * (fire and forget) or a stage of an edge pipeline that hands its output to the
	 * next stage, which then arrives at this host.
	 *
	 * @param attempt an attempt that ran
	 */
	void finishAtHost(Attempt attempt);

	/**
	 * Drops an attempt on its host: it does not start if it has not, and its
	 * result is discarded if it is running. Code running on the host calls it.
	 *
	 * @param attempt the attempt
	 */
	void cancel(Attempt attempt);

	// ----------------------------------------------------------- instances

	/**
	 * Starts an instance of a service on a host. It holds
	 * {@link Service#memory} there and is ready after {@link Service#startup}.
	 * An attempt that arrives while it starts waits for it.
	 *
	 * @param host    the node that runs it
	 * @param service the service
	 * @param onReady runs when it is ready, or at once when it already is; may be
	 *                {@code null}
	 * @return whether it runs or starts: {@code false} when the host lacks the
	 *         memory or is down
	 */
	boolean startInstance(ComputingNode host, Service service, Runnable onReady);

	/**
	 * Stops an instance, and frees its memory. Attempts waiting for it are lost.
	 *
	 * @param host    its host
	 * @param service the service
	 */
	void stopInstance(ComputingNode host, Service service);

	/**
	 * @param host    a node
	 * @param service a service
	 * @return whether an instance of the service runs or starts on it
	 */
	boolean hasInstance(ComputingNode host, Service service);

	/**
	 * @param host a node
	 * @return the memory still free for instances on it, in megabytes
	 */
	double freeMemory(ComputingNode host);
}
