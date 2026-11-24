package zip.hedlund.pureedgesim.edgesdk.fault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The ground-truth log of every fault of a run. It is read by the metrics and
 * by tests; algorithms never see it.
 */
public final class FaultLog {

	private final List<FaultRecord> records = new ArrayList<>();

	/** Opens a record and returns its id. */
	public synchronized int open(FaultType type, String target, String cause, double start, List<String> nodes,
			String groups) {
		int id = records.size();
		records.add(new FaultRecord(id, type, target, cause, start, Double.POSITIVE_INFINITY, nodes, groups));
		return id;
	}

	/** Closes a record. */
	public synchronized void close(int id, double end) {
		FaultRecord r = records.get(id);
		if (r.end() == Double.POSITIVE_INFINITY)
			records.set(id, r.endedAt(end));
	}

	/** @return every record, ordered by start time then id */
	public synchronized List<FaultRecord> records() {
		List<FaultRecord> copy = new ArrayList<>(records);
		copy.sort(Comparator.comparingDouble(FaultRecord::start).thenComparingInt(FaultRecord::id));
		return copy;
	}

	/** @return the records of one type */
	public List<FaultRecord> of(FaultType type) {
		return records().stream().filter(r -> r.type() == type).toList();
	}

	/** @return true when a partition is active at {@code time} */
	public boolean partitionActive(double time) {
		return records().stream().anyMatch(r -> r.type() == FaultType.PARTITION && r.activeAt(time));
	}

	/** @return true when any fault (emergent range losses excluded) is active at {@code time} */
	public boolean faultActive(double time) {
		return records().stream().anyMatch(r -> !"mobility".equals(r.cause()) && r.activeAt(time));
	}
}
