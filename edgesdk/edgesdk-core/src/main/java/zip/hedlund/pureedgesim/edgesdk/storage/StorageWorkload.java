package zip.hedlund.pureedgesim.edgesdk.storage;

import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;

/**
 * Reads and writes issued by the clients (end devices) against a key space with
 * a Zipf popularity. Operations are generated before the run from the workload
 * random stream, so every compared strategy faces the same operations.
 *
 * <pre>{@code
 * StorageWorkload.zipf(1_000, 0.9).reads(5).writes(1).valueBytes(4_096).timeout(2)
 * }</pre>
 */
public final class StorageWorkload {

	private final int keys;
	private final double zipfExponent;
	private final double readsPerMinute;
	private final double writesPerMinute;
	private final long valueBytes;
	private final double timeoutS;
	private final Select hosts;
	private final Select clients;

	private StorageWorkload(int keys, double zipfExponent, double readsPerMinute, double writesPerMinute,
			long valueBytes, double timeoutS, Select hosts, Select clients) {
		if (keys < 1 || zipfExponent < 0 || readsPerMinute < 0 || writesPerMinute < 0 || valueBytes < 0
				|| !(timeoutS > 0))
			throw new IllegalArgumentException("invalid storage workload");
		this.keys = keys;
		this.zipfExponent = zipfExponent;
		this.readsPerMinute = readsPerMinute;
		this.writesPerMinute = writesPerMinute;
		this.valueBytes = valueBytes;
		this.timeoutS = timeoutS;
		this.hosts = hosts;
		this.clients = clients;
	}

	/**
	 * @param keys     number of keys
	 * @param exponent Zipf exponent; 0 is uniform, ~1 is typical web popularity
	 */
	public static StorageWorkload zipf(int keys, double exponent) {
		return new StorageWorkload(keys, exponent, 6, 1, 1_024, 2, Select.tier(Tier.EDGE_STATIC, Tier.EDGE_MOBILE,
				Tier.CLOUD), Select.tier(Tier.END_DEVICE));
	}

	/** Uniform popularity. */
	public static StorageWorkload uniform(int keys) {
		return zipf(keys, 0);
	}

	/** @param perMinute reads per minute and client */
	public StorageWorkload reads(double perMinute) {
		return new StorageWorkload(keys, zipfExponent, perMinute, writesPerMinute, valueBytes, timeoutS, hosts, clients);
	}

	/** @param perMinute writes per minute and client */
	public StorageWorkload writes(double perMinute) {
		return new StorageWorkload(keys, zipfExponent, readsPerMinute, perMinute, valueBytes, timeoutS, hosts, clients);
	}

	public StorageWorkload valueBytes(long bytes) {
		return new StorageWorkload(keys, zipfExponent, readsPerMinute, writesPerMinute, bytes, timeoutS, hosts, clients);
	}

	/** @param seconds an operation not answered within this time is unavailable */
	public StorageWorkload timeout(double seconds) {
		return new StorageWorkload(keys, zipfExponent, readsPerMinute, writesPerMinute, valueBytes, seconds, hosts,
				clients);
	}

	/** @param select the nodes that host storage (default: every edge node and cloud) */
	public StorageWorkload hostedOn(Select select) {
		return new StorageWorkload(keys, zipfExponent, readsPerMinute, writesPerMinute, valueBytes, timeoutS, select,
				clients);
	}

	/** @param select the nodes that issue operations (default: every end device) */
	public StorageWorkload issuedBy(Select select) {
		return new StorageWorkload(keys, zipfExponent, readsPerMinute, writesPerMinute, valueBytes, timeoutS, hosts,
				select);
	}

	public int keys() {
		return keys;
	}

	public double zipfExponent() {
		return zipfExponent;
	}

	public double readsPerMinute() {
		return readsPerMinute;
	}

	public double writesPerMinute() {
		return writesPerMinute;
	}

	public long valueBytes() {
		return valueBytes;
	}

	public double timeoutS() {
		return timeoutS;
	}

	public Select hosts() {
		return hosts;
	}

	public Select clients() {
		return clients;
	}

	/** @return the name of key {@code i} */
	public static String keyName(int i) {
		return "k" + i;
	}
}
