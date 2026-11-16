package partitiontestbed.flocky;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import partitiontestbed.Service;
import partitiontestbed.Trait;

/**
 * The part of the Open Application Model that Flocky orchestrates with, reduced
 * to what a placement decision reads.
 * <p>
 * Each service of the testbed is one OAM component, with several
 * {@link Definition}s, the implementations of the paper (A.1, A.2, ...), each
 * with its own resource needs and its own traits, for example a unikernel that
 * only a server with a secure runtime can run. The traits a server advertises and
 * the definitions it runs are what the repository exchanges in a
 * {@link NodeSummary}.
 *
 * @see FlockyAgent
 */
public final class Oam {

	private Oam() {
	}

	/** The workload type a definition needs, which decides the runtime. */
	public enum WorkloadType {
		/** An OCI container. */
		CONTAINER,
		/** An OSv unikernel, which needs {@link Trait#SECURE_RUNTIME}. */
		UNIKERNEL
	}

	/** One ComponentDefinition: a deployable implementation of a service. */
	public static final class Definition {
		/** Unique identifier across the catalog. */
		public final int id;
		/** The service it implements. */
		public final Service service;
		/** Name in the notation of the paper, for example {@code C0.1}. */
		public final String name;
		/** Its runtime. */
		public final WorkloadType workload;
		/** Traits the server must advertise, beyond those of the service. */
		public final Set<Trait> required;
		/** Memory reserved on the server while one instance runs, in megabytes. */
		public final double memory;

		Definition(int id, Service service, String name, WorkloadType workload, Set<Trait> required, double memory) {
			this.id = id;
			this.service = service;
			this.name = name;
			this.workload = workload;
			this.required = Collections.unmodifiableSet(required);
			this.memory = memory;
		}

		/**
		 * @param traits the traits a server advertises
		 * @return whether a server with these traits may run this definition
		 */
		public boolean fits(Set<Trait> traits) {
			return service.fits(traits) && traits.containsAll(required);
		}

		@Override
		public String toString() {
			return name;
		}
	}

	/**
	 * What a server tells the others about itself through the repository API: a
	 * snapshot, so that it is as old as the last time it was fetched.
	 */
	public static final class NodeSummary {
		/** Traits the server advertises. */
		public final Set<Trait> traits;
		/** Memory the server lends to deployments, in megabytes. */
		public final double memoryCapacity;
		/** Memory still free for new deployments, in megabytes. */
		public final double freeMemory;
		/** Definitions it runs that can take one more client. */
		public final Set<Integer> openInstances;
		/** Simulation time at which the snapshot was taken. */
		public final double time;

		NodeSummary(Set<Trait> traits, double memoryCapacity, double freeMemory, Set<Integer> openInstances,
				double time) {
			this.traits = traits;
			this.memoryCapacity = memoryCapacity;
			this.freeMemory = freeMemory;
			this.openInstances = openInstances;
			this.time = time;
		}
	}

	/** Every definition of the run. */
	public static final class Catalog {
		/** Every definition, indexed by its identifier. */
		public final List<Definition> definitions = new ArrayList<>();

		/**
		 * Draws the definitions of every service from the configuration.
		 *
		 * @param config   the Flocky configuration
		 * @param services the services of the testbed
		 * @param random   the random source of Flocky
		 * @return the catalog of the run
		 */
		static Catalog generate(FlockyConfig config, List<Service> services, Random random) {
			Catalog catalog = new Catalog();
			for (Service service : services)
				for (int i = 0; i < config.implementationsPerService; i++) {
					boolean unikernel = random.nextDouble() < config.unikernelProbability;
					Set<Trait> traits = unikernel ? EnumSet.of(Trait.SECURE_RUNTIME) : EnumSet.noneOf(Trait.class);
					double memory = config.minDefinitionMemory
							+ random.nextDouble() * (config.maxDefinitionMemory - config.minDefinitionMemory);
					catalog.definitions.add(new Definition(catalog.definitions.size(), service,
							"C" + service.id + "." + (i + 1), unikernel ? WorkloadType.UNIKERNEL : WorkloadType.CONTAINER,
							traits, Math.round(memory)));
				}
			return catalog;
		}
	}
}
