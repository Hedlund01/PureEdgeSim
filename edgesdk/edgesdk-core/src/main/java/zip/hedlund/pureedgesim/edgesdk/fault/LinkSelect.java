package zip.hedlund.pureedgesim.edgesdk.fault;

import java.util.Set;

import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.LinkRef;

/**
 * Selects links of a scenario. Build one with {@link Targets}.
 */
@FunctionalInterface
public interface LinkSelect {

	Set<LinkRef> resolve(DesignTopology topology);
}
