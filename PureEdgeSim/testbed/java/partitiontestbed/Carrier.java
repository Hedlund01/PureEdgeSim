package partitiontestbed;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.taskgenerator.DefaultTask;

/**
 * What the testbed puts on the network: a message of a strategy, a task on its
 * way to a server or a host, or a result on its way back. The framework moves
 * tasks over its links, so each of these rides in a task of its own, which the
 * links carry like any other and which {@link TestbedNetworkModel} hands to the
 * {@link TaskEngine} when it arrives.
 */
final class Carrier extends DefaultTask {

	/** What is carried. */
	enum Kind {
		/** A message of the strategy. */
		MESSAGE,
		/** The request of an RPC. */
		RPC_REQUEST,
		/** The reply of an RPC. */
		RPC_REPLY,
		/** A task from its device to a server. */
		REQUEST,
		/** An attempt from the node that dispatched it to its host. */
		DISPATCH,
		/** A result from its host to its device. */
		RESULT
	}

	private static int counter;

	final Kind kind;
	final ComputingNode from;
	final ComputingNode to;
	final long bytes;
	/** The payload, the {@link Job} or the {@link Attempt}. */
	final Object payload;
	/** The RPC it belongs to, for its request and its reply. */
	final long rpc;

	Carrier(Kind kind, ComputingNode from, ComputingNode to, long bytes, Object payload, long rpc) {
		// Negative identifiers, apart from the tasks of the devices.
		super(-(++counter));
		this.kind = kind;
		this.from = from;
		this.to = to;
		this.bytes = Math.max(1, bytes);
		this.payload = payload;
		this.rpc = rpc;
		setFileSizeInBits(this.bytes * 8);
		setEdgeDevice(from);
	}

	/** @return whether it carries control traffic of the strategy, rather than a task or a result */
	boolean control() {
		return kind == Kind.MESSAGE || kind == Kind.RPC_REQUEST || kind == Kind.RPC_REPLY;
	}
}
