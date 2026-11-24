package zip.hedlund.pureedgesim.edgesdk.fault;

import java.util.List;

/**
 * One fault, as it really happened (ground truth).
 *
 * @param id     sequence number in the run
 * @param type   kind of fault
 * @param target what was hit: a node, a link, or the partition groups
 * @param cause  {@code scripted}, {@code stochastic}, {@code mobility}, {@code battery}
 *               or the label of a custom model
 * @param start  when it started
 * @param end    when it ended; infinity if it never did (within the run)
 * @param nodes  nodes involved: the crashed node, the link endpoints, or every
 *               node of the partition
 * @param groups for a partition, its groups as {@code a,b|c,d}; empty otherwise
 */
public record FaultRecord(int id, FaultType type, String target, String cause, double start, double end,
		List<String> nodes, String groups) {

	public FaultRecord {
		nodes = List.copyOf(nodes);
	}

	/** @return true when the fault is active at {@code time} */
	public boolean activeAt(double time) {
		return time >= start && time < end;
	}

	public double duration() {
		return end - start;
	}

	FaultRecord endedAt(double time) {
		return new FaultRecord(id, type, target, cause, start, time, nodes, groups);
	}
}
