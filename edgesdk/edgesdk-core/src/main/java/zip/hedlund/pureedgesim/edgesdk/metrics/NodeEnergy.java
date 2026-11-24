package zip.hedlund.pureedgesim.edgesdk.metrics;

/**
 * Energy used by one node during a run.
 *
 * @param node      node name
 * @param tier      tier name
 * @param computeWh CPU energy, idle included (and propulsion, if modelled)
 * @param networkWh radio and port energy
 * @param batteryPctLeft battery left, or NaN on mains power
 * @param died      the battery ran out
 */
public record NodeEnergy(String node, String tier, double computeWh, double networkWh, double batteryPctLeft,
		boolean died) {

	public double totalWh() {
		return computeWh + networkWh;
	}
}
