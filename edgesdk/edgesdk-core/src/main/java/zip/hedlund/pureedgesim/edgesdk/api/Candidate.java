package zip.hedlund.pureedgesim.edgesdk.api;

import java.util.Objects;
import java.util.function.Supplier;

import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.LocalFirst;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageStrategy;
import zip.hedlund.pureedgesim.edgesdk.storage.strategies.PrimaryBackup;

/**
 * A named set of algorithms to evaluate: an offloading policy and, when the
 * scenario has a storage workload, a storage strategy. The suppliers create a
 * fresh instance for every run, so no state leaks between runs.
 *
 * <pre>{@code
 * Candidate.of("mine", MyOffloading::new).storage(MyReplication::new)
 * }</pre>
 *
 * @param name          unique name in an experiment
 * @param orchestration creates the offloading policy
 * @param storage       creates the storage strategy (used only with a storage workload)
 */
public record Candidate(String name, Supplier<? extends OrchestrationPolicy> orchestration,
		Supplier<? extends StorageStrategy> storage) {

	public Candidate {
		Objects.requireNonNull(name, "name");
		if (name.isBlank() || name.contains(","))
			throw new IllegalArgumentException("candidate names must be non-blank and contain no comma: " + name);
		if (orchestration == null)
			orchestration = LocalFirst::new;
		if (storage == null)
			storage = () -> new PrimaryBackup(2);
	}

	/** A candidate with an offloading policy (and the default storage strategy). */
	public static Candidate of(String name, Supplier<? extends OrchestrationPolicy> orchestration) {
		return new Candidate(name, orchestration, null);
	}

	/** A candidate that only brings a storage strategy (tasks run locally). */
	public static Candidate storageOnly(String name, Supplier<? extends StorageStrategy> storage) {
		return new Candidate(name, null, storage);
	}

	/** @return a copy with this storage strategy */
	public Candidate storage(Supplier<? extends StorageStrategy> strategy) {
		return new Candidate(name, orchestration, strategy);
	}
}
