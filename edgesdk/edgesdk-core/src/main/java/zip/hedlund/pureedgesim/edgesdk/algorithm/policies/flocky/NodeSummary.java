package zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky;

import java.util.List;
import java.util.Map;
import java.util.Set;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * Flocky's NodeSummary: what a node advertises about itself through the
 * Repository API. Other nodes only know it as of the last time they fetched it.
 *
 * @param node          the node
 * @param time          when the node built it
 * @param deploys       whether the node runs the Deployment service
 * @param cores         the node's cores
 * @param ramMb         the node's RAM
 * @param reservedCores cores reserved by deployed definitions
 * @param reservedRamMb RAM reserved by deployed definitions
 * @param load          busy cores over cores when it was built
 * @param traits        traits the node's capability providers advertise
 * @param workloadTypes workload types the node can run
 * @param deployed      definitions deployed on the node, by name, with their number of clients
 * @param definitions   the definitions in the node's repository
 */
public record NodeSummary(NodeRef node, double time, boolean deploys, double cores, double ramMb,
		double reservedCores, double reservedRamMb, double load, Set<String> traits, Set<String> workloadTypes,
		Map<String, Integer> deployed, List<ComponentDefinition> definitions) {

	public NodeSummary {
		traits = Set.copyOf(traits);
		workloadTypes = Set.copyOf(workloadTypes);
		deployed = Map.copyOf(deployed);
		definitions = List.copyOf(definitions);
	}

	public double freeCores() {
		return cores - reservedCores;
	}

	public double freeRamMb() {
		return ramMb - reservedRamMb;
	}

	/** @return true when the definition is deployed here */
	public boolean hasDeployed(ComponentDefinition def) {
		return deployed.containsKey(def.name());
	}

	/**
	 * @return true when a deployment of {@code def} here would meet the component's
	 *         required traits and the node can run its workload type
	 */
	public boolean matches(Component component, ComponentDefinition def) {
		if (!deploys || !workloadTypes.contains(def.workloadType()))
			return false;
		for (String t : component.required())
			if (!traits.contains(t) && !def.traits().contains(t))
				return false;
		return true;
	}

	/** @return true when {@code def} is deployed here, or fits in what is not reserved */
	public boolean fits(ComponentDefinition def) {
		return hasDeployed(def) || (def.cores() <= freeCores() && def.ramMb() <= freeRamMb());
	}

	/** @return the size on the wire, bytes */
	long bytes() {
		return 128 + 16L * (traits.size() + workloadTypes.size() + deployed.size())
				+ ComponentDefinition.BYTES * definitions.size();
	}
}
