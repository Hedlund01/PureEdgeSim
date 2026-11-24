package zip.hedlund.pureedgesim.edgesdk.storage;

import java.util.Map;
import java.util.TreeMap;

/**
 * An immutable vector clock, keyed by node name.
 */
public final class VectorClock {

	/** How two clocks relate. */
	public enum Order {
		BEFORE, AFTER, EQUAL, CONCURRENT
	}

	public static final VectorClock EMPTY = new VectorClock(Map.of());

	private final Map<String, Long> entries;

	private VectorClock(Map<String, Long> entries) {
		this.entries = Map.copyOf(entries);
	}

	/** @return a copy with the counter of {@code node} incremented */
	public VectorClock increment(String node) {
		Map<String, Long> copy = new TreeMap<>(entries);
		copy.merge(node, 1L, Long::sum);
		return new VectorClock(copy);
	}

	/** @return the entry-wise maximum of the two clocks */
	public VectorClock merge(VectorClock other) {
		Map<String, Long> copy = new TreeMap<>(entries);
		other.entries.forEach((k, v) -> copy.merge(k, v, Math::max));
		return new VectorClock(copy);
	}

	public long get(String node) {
		return entries.getOrDefault(node, 0L);
	}

	public Map<String, Long> entries() {
		return entries;
	}

	/** @return how this clock relates to {@code other} */
	public Order compare(VectorClock other) {
		boolean less = false;
		boolean greater = false;
		TreeMap<String, Long> keys = new TreeMap<>(entries);
		other.entries.forEach(keys::putIfAbsent);
		for (String k : keys.keySet()) {
			long a = get(k);
			long b = other.get(k);
			if (a < b)
				less = true;
			else if (a > b)
				greater = true;
		}
		if (less && greater)
			return Order.CONCURRENT;
		if (less)
			return Order.BEFORE;
		if (greater)
			return Order.AFTER;
		return Order.EQUAL;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof VectorClock v && v.entries.equals(entries);
	}

	@Override
	public int hashCode() {
		return entries.hashCode();
	}

	@Override
	public String toString() {
		return new TreeMap<>(entries).toString();
	}
}
