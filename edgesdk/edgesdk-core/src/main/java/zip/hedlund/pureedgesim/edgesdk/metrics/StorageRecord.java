package zip.hedlund.pureedgesim.edgesdk.metrics;

/**
 * One storage operation of a run.
 *
 * @param id              operation id
 * @param write           true for a write, false for a read
 * @param key             the key
 * @param client          issuing device
 * @param issuedAt        issue time
 * @param answeredAt      time of the answer, or NaN if none in time
 * @param ok              answered in time and successful (a read that found the
 *                        key, or found nothing when nothing was ever committed)
 * @param versionsBehind  for reads: committed writes newer than the value returned
 * @param stalenessS      for reads: how long the returned value had been outdated (time
 *                        since the first newer write was acknowledged)
 * @param duringPartition a partition was active at issue time
 */
public record StorageRecord(long id, boolean write, String key, String client, double issuedAt, double answeredAt,
		boolean ok, int versionsBehind, double stalenessS, boolean duringPartition) {

	public double latency() {
		return answeredAt - issuedAt;
	}
}
