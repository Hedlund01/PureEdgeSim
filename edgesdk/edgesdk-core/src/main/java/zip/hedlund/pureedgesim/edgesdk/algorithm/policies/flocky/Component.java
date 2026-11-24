package zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky;

import java.util.Objects;
import java.util.Set;

/**
 * An OAM Component: what an application asks for, independent of how it is
 * implemented. Flocky picks one of the {@link ComponentDefinition}s whose
 * {@code baseComponent} is this name. A task of the workload of the same name is
 * a request to this component.
 *
 * @param name     the component name, also the name of its workload
 * @param required traits the deployment must have (from the node or the definition)
 * @param optional traits that raise the score of a deployment but are not needed
 */
public record Component(String name, Set<String> required, Set<String> optional) {

	public Component {
		Objects.requireNonNull(name, "name");
		required = Set.copyOf(required);
		optional = Set.copyOf(optional);
	}

	/** A component with no trait requirements. */
	public static Component of(String name) {
		return new Component(name, Set.of(), Set.of());
	}

	public Component requires(String... traits) {
		return new Component(name, Set.of(traits), optional);
	}

	public Component prefers(String... traits) {
		return new Component(name, required, Set.of(traits));
	}
}
