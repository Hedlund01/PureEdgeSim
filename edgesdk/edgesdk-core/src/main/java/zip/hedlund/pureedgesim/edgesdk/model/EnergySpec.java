package zip.hedlund.pureedgesim.edgesdk.model;

/**
 * Power model of a node.
 * <p>
 * Computation follows the PureEdgeSim linear model: {@code idleW} all the time,
 * plus {@code maxW - idleW} while a core runs a task. Network energy is charged
 * per bit by the channel ({@link ChannelSpec#joulesPerBit()}). {@code propulsionW}
 * is a constant extra drain, e.g. the motors of a UAV; 0 leaves it out.
 *
 * @param battery     true when the node runs on a battery; it dies when empty
 * @param capacityWh  battery capacity in watt-hours (ignored without battery)
 * @param idleW       idle power in watts
 * @param maxW        power at full CPU load in watts
 * @param propulsionW constant extra drain in watts
 */
public record EnergySpec(boolean battery, double capacityWh, double idleW, double maxW, double propulsionW) {

	public EnergySpec {
		if (idleW < 0 || maxW < idleW || propulsionW < 0)
			throw new IllegalArgumentException("need 0 <= idleW <= maxW and propulsionW >= 0");
		if (battery && !(capacityWh > 0))
			throw new IllegalArgumentException("a battery needs a positive capacityWh");
	}

	/** A node on mains or vehicle power: never runs out. */
	public static EnergySpec mains(double idleW, double maxW) {
		return new EnergySpec(false, 0, idleW, maxW, 0);
	}

	/** A battery-powered node. */
	public static EnergySpec battery(double capacityWh, double idleW, double maxW) {
		return new EnergySpec(true, capacityWh, idleW, maxW, 0);
	}

	public EnergySpec withCapacityWh(double wh) {
		return new EnergySpec(battery, wh, idleW, maxW, propulsionW);
	}

	public EnergySpec withPower(double idle, double max) {
		return new EnergySpec(battery, capacityWh, idle, max, propulsionW);
	}

	public EnergySpec withPropulsionW(double watts) {
		return new EnergySpec(battery, capacityWh, idleW, maxW, watts);
	}
}
