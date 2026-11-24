package zip.hedlund.pureedgesim.edgesdk.storage.strategies;

import java.util.ArrayList;
import java.util.List;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageOps;

/** Helpers to place keys on storage hosts. */
public final class Replicas {

	private Replicas() {
	}

	/**
	 * @return {@code n} hosts for a key: consecutive hosts on a ring, starting at
	 *         the key's hash (design information, the same for every node)
	 */
	public static List<NodeRef> forKey(StorageOps ops, String key, int n) {
		List<NodeRef> hosts = ops.storageNodes();
		int size = hosts.size();
		int start = Math.floorMod(key.hashCode() * 0x9E3779B1, size);
		List<NodeRef> out = new ArrayList<>();
		for (int i = 0; i < Math.min(n, size); i++)
			out.add(hosts.get((start + i) % size));
		return out;
	}
}
