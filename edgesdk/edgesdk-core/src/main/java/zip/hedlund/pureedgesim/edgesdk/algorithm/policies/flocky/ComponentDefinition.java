package zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky;

import java.util.Objects;
import java.util.Set;

/**
 * An OAM ComponentDefinition with Flocky's Schematic: one implementation of a
 * {@link Component}. Several definitions can implement the same base component
 * with different resources, workload types and traits (e.g. a container and a
 * secure unikernel build).
 *
 * @param name          unique name of this implementation
 * @param baseComponent the component it implements
 * @param workloadType  the runtime it needs, e.g. {@value #CONTAINER}; a node must support it
 * @param cores         cores reserved on the node while deployed
 * @param ramMb         RAM reserved on the node while deployed
 * @param traits        traits this implementation provides itself (e.g. {@code secure-runtime})
 */
public record ComponentDefinition(String name, String baseComponent, String workloadType, double cores,
		double ramMb, Set<String> traits) {

	/** The default workload type. */
	public static final String CONTAINER = "container";

	/** Size of a definition on the wire, bytes. */
	static final long BYTES = 256;

	public ComponentDefinition {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(baseComponent, "baseComponent");
		Objects.requireNonNull(workloadType, "workloadType");
		if (cores < 0 || ramMb < 0)
			throw new IllegalArgumentException(name + ": resources must not be negative");
		traits = Set.copyOf(traits);
	}

	/** A container implementation with no traits of its own. */
	public static ComponentDefinition of(String name, String baseComponent, double cores, double ramMb) {
		return new ComponentDefinition(name, baseComponent, CONTAINER, cores, ramMb, Set.of());
	}

	/** The implementation used for a workload that has no definition: a container that reserves nothing. */
	static ComponentDefinition generic(String component) {
		return of(component + "/generic", component, 0, 0);
	}

	public ComponentDefinition withWorkloadType(String type) {
		return new ComponentDefinition(name, baseComponent, type, cores, ramMb, traits);
	}

	public ComponentDefinition provides(String... newTraits) {
		return new ComponentDefinition(name, baseComponent, workloadType, cores, ramMb, Set.of(newTraits));
	}
}
