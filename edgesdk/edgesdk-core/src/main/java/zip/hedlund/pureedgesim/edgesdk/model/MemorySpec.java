package zip.hedlund.pureedgesim.edgesdk.model;

/**
 * Memory and storage of a node, in megabytes.
 *
 * @param ramMb     RAM; a running task holds its container size of RAM
 * @param storageMb persistent storage
 */
public record MemorySpec(double ramMb, double storageMb) {

	public MemorySpec {
		if (!(ramMb > 0) || !(storageMb > 0))
			throw new IllegalArgumentException("ramMb and storageMb must be positive, were " + ramMb + ", " + storageMb);
	}

	public static MemorySpec of(double ramMb, double storageMb) {
		return new MemorySpec(ramMb, storageMb);
	}

	/** Convenience constructor in gigabytes. */
	public static MemorySpec gb(double ramGb, double storageGb) {
		return new MemorySpec(ramGb * 1024, storageGb * 1024);
	}

	public MemorySpec withRamMb(double ram) {
		return new MemorySpec(ram, storageMb);
	}

	public MemorySpec withStorageMb(double storage) {
		return new MemorySpec(ramMb, storage);
	}
}
