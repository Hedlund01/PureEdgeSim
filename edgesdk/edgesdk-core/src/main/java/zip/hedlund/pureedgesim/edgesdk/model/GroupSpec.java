package zip.hedlund.pureedgesim.edgesdk.model;

import java.util.Set;
import java.util.TreeSet;

/**
 * A named set of nodes, e.g. a region. Groups are used to target faults
 * ("partition region A from the cloud") and are visible to algorithms as design
 * information.
 */
public record GroupSpec(String name, Set<String> members) {

	public GroupSpec {
		members = Set.copyOf(new TreeSet<>(members));
	}
}
