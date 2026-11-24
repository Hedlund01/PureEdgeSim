package zip.hedlund.pureedgesim.edgesdk.model;

/**
 * Processing capacity of a node.
 *
 * @param cores       number of CPU cores, at least 1 (a task occupies one core)
 * @param mipsPerCore speed of one core in million instructions per second
 */
public record ComputeSpec(int cores, double mipsPerCore) {

	public ComputeSpec {
		if (cores < 1)
			throw new IllegalArgumentException("cores must be at least 1, was " + cores);
		if (!(mipsPerCore > 0))
			throw new IllegalArgumentException("mipsPerCore must be positive, was " + mipsPerCore);
	}

	public static ComputeSpec of(int cores, double mipsPerCore) {
		return new ComputeSpec(cores, mipsPerCore);
	}

	public ComputeSpec withCores(int cores) {
		return new ComputeSpec(cores, mipsPerCore);
	}

	public ComputeSpec withMipsPerCore(double mips) {
		return new ComputeSpec(cores, mips);
	}

	/** @return total MIPS of the node */
	public double totalMips() {
		return cores * mipsPerCore;
	}
}
