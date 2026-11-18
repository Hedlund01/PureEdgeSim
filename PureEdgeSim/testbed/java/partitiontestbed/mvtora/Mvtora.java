package partitiontestbed.mvtora;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The models and the algorithms of MVTORA (Sun et al., "Joint Task Offloading
 * and Resource Allocation in Aerial-Terrestrial UAV Networks With Edge and Fog
 * Computing for Post-Disaster Rescue", IEEE Transactions on Mobile Computing
 * 23(9), 2024), free of the testbed: what the controller computes once it has
 * the state of the devices and of the fog nodes.
 * <p>
 * Units: sizes in bits, work in gigacycles (a million instructions of the
 * testbed is a million cycles), speeds in GHz, rates in bits per second, times
 * in seconds, energy in joules.
 * <ul>
 * <li>delay and energy of the three ways to run a task: locally (eq. 11 and 12),
 * on the controller's MEC server (eq. 13 and 14), divided over fog nodes (eq. 15
 * and 16);</li>
 * <li>the utility of each (eq. 17 to 21);</li>
 * <li>Algorithm 1, the task offloading game, by better responses;</li>
 * <li>Algorithm 2, the MEC resource allocation, by bisection on the multiplier of
 * the capacity constraint and the closed form of eq. 30;</li>
 * <li>Algorithm 3, the selection of fog nodes, by preference (eq. 33);</li>
 * <li>Algorithm 4, the division of a task among them, by a genetic algorithm
 * with elitism, 2-tournament selection, arithmetic crossover (eq. 36), uniform
 * mutation (eq. 37) and normalisation (eq. 35).</li>
 * </ul>
 * A way whose delay exceeds the time the task has left breaks constraint (22j):
 * its utility is {@link #infeasible}, below any feasible utility and ordered by
 * delay, so that a task with no feasible way takes the fastest one.
 */
final class Mvtora {

	/** A task as the controller sees it. */
	static final class Task {
		/** D_n: its input, in bits. */
		final double bits;
		/** eta_n D_n: its work, in gigacycles. */
		final double work;
		/** T_n^max: the time it has left, in seconds. */
		final double timeLeft;

		Task(double bits, double work, double timeLeft) {
			this.bits = bits;
			this.work = work;
			this.timeLeft = timeLeft;
		}
	}

	/** A fog node as a device reported it. */
	static final class Fog {
		/** R_{n,j}: the rate of the link to it, in bits per second. */
		final double rate;
		/** f_j^veh: the speed it runs a part at, in GHz. */
		final double speed;
		/** The time a part waits there before it runs, in seconds: 0 with an idle core. */
		final double wait;

		Fog(double rate, double speed, double wait) {
			this.rate = rate;
			this.speed = speed;
			this.wait = wait;
		}
	}

	/** A player of the offloading game: one device with a task in this round. */
	static final class Player {
		final Task task;
		/** R_{n,u}: the rate of its link to the controller. */
		final double mecRate;
		/** U_n^ini: the utility of its initial decision, local or fog. */
		final double initialUtility;

		Player(Task task, double mecRate, double initialUtility) {
			this.task = task;
			this.mecRate = mecRate;
			this.initialUtility = initialUtility;
		}
	}

	/** What the game decided. */
	static final class Outcome {
		/** For each player, whether it offloads to the controller. */
		final boolean[] mec;
		/** F_n: the speed allocated to each player that does, in GHz. */
		final double[] allocation;
		/** U_n: the utility of each player. */
		final double[] utility;
		/** Iterations until no player changed. */
		final int iterations;

		Outcome(boolean[] mec, double[] allocation, double[] utility, int iterations) {
			this.mec = mec;
			this.allocation = allocation;
			this.utility = utility;
			this.iterations = iterations;
		}
	}

	private static final double INFEASIBLE = -1e9;

	private final MvtoraConfig config;

	Mvtora(MvtoraConfig config) {
		this.config = config;
	}

	// ------------------------------------------------------------- utility

	/**
	 * @return the utility of eq. 21 for a way to run a task, or
	 *         {@link #infeasible} when it is too slow
	 */
	double utility(Task task, double delay, double energy, double ghz) {
		if (!(delay <= task.timeLeft))
			return infeasible(delay);
		return config.alpha * Math.log(config.beta + task.timeLeft - delay) - config.energyWeight * energy
				- config.pricePerGhz * ghz;
	}

	/** @return the utility of a way that misses the deadline: below any other, the slower the lower */
	static double infeasible(double delay) {
		return INFEASIBLE - (Double.isFinite(delay) ? Math.min(delay, 1e6) : 1e6);
	}

	/** @return whether a utility is that of a way that meets the deadline */
	static boolean feasible(double utility) {
		return utility > INFEASIBLE / 2;
	}

	// --------------------------------------------------------------- local

	/** Eq. 11, plus the wait for a core of the device. */
	double localDelay(Task task, double speed, double wait) {
		return speed > 0 ? wait + task.work / speed : Double.POSITIVE_INFINITY;
	}

	/** Eq. 12: k f^3 T, which is k f^2 times the cycles. */
	double localEnergy(Task task, double speed) {
		double hertz = speed * 1e9;
		return config.capacitance * hertz * hertz * task.work * 1e9;
	}

	double localUtility(Task task, double speed, double wait) {
		if (speed <= 0)
			return infeasible(Double.POSITIVE_INFINITY);
		return utility(task, localDelay(task, speed, wait), localEnergy(task, speed), 0);
	}

	// ----------------------------------------------------------------- MEC

	/** Eq. 13. */
	double mecDelay(Task task, double rate, double allocation) {
		return allocation > 0 && rate > 0 ? task.bits / rate + task.work / allocation : Double.POSITIVE_INFINITY;
	}

	/** Eq. 14. */
	double mecEnergy(Task task, double rate) {
		return rate > 0 ? config.transmitPower * task.bits / rate : Double.POSITIVE_INFINITY;
	}

	double mecUtility(Task task, double rate, double allocation) {
		return utility(task, mecDelay(task, rate, allocation), mecEnergy(task, rate), allocation);
	}

	/**
	 * Eq. 30: the allocation that maximises the utility of one task, for a price
	 * {@code gamma} of the capacity constraint, capped at what one task can use.
	 */
	double optimalAllocation(Task task, double rate, double gamma, double cap) {
		double c = config.beta + task.timeLeft - task.bits / rate;
		if (!(c > 0) || task.work <= 0)
			return 0;
		double price = config.pricePerGhz + gamma;
		if (price <= 0)
			return cap;
		double w = task.work;
		double f = (w + Math.sqrt(w * w + 4 * c * config.alpha * w / price)) / (2 * c);
		return Math.min(Math.max(f, 0), cap);
	}

	/**
	 * Algorithm 2: allocates the capacity of the controller to the tasks offloaded
	 * to it, by bisection on gamma until they fit in it.
	 *
	 * @param tasks    the tasks offloaded to the controller
	 * @param rates    the rate of the link of each to the controller
	 * @param capacity F_u^max: what the controller has, in GHz
	 * @param cap      the most one task can use: the speed of one core
	 * @return F_n of each task
	 */
	double[] allocate(List<Task> tasks, double[] rates, double capacity, double cap) {
		int n = tasks.size();
		double[] allocation = allocationAt(tasks, rates, 0, cap);
		if (sum(allocation) <= capacity)
			return allocation;
		double low = 0;
		double high = config.gammaBound;
		allocation = allocationAt(tasks, rates, high, cap);
		while (high - low >= config.bisectionAccuracy) {
			double gamma = (low + high) / 2;
			double[] at = allocationAt(tasks, rates, gamma, cap);
			if (sum(at) >= capacity) {
				low = gamma;
			} else {
				high = gamma;
				allocation = at;
			}
		}
		// Still too much at the bound: share what there is in proportion.
		double total = sum(allocation);
		if (total > capacity && total > 0)
			for (int i = 0; i < n; i++)
				allocation[i] *= capacity / total;
		return allocation;
	}

	private double[] allocationAt(List<Task> tasks, double[] rates, double gamma, double cap) {
		double[] allocation = new double[tasks.size()];
		for (int i = 0; i < allocation.length; i++)
			allocation[i] = optimalAllocation(tasks.get(i), rates[i], gamma, cap);
		return allocation;
	}

	private static double sum(double[] values) {
		double total = 0;
		for (double value : values)
			total += value;
		return total;
	}

	// -------------------------------------------------------------- game

	/**
	 * Algorithm 1: the task offloading game. Each player in turn tries the
	 * controller, the capacity of the controller is allocated again among those
	 * that use it (Algorithm 2), and the player stays there only if it gains on its
	 * initial decision; until no player changes.
	 *
	 * @param players  the devices with a task in this round
	 * @param capacity F_u^max, in GHz
	 * @param cap      the most one task can use, in GHz
	 * @return the decision of each player
	 */
	Outcome play(List<Player> players, double capacity, double cap) {
		int n = players.size();
		boolean[] mec = new boolean[n];
		int iterations = 0;
		boolean changed = true;
		while (changed && iterations < config.maxGameIterations) {
			iterations++;
			boolean[] before = mec.clone();
			for (int i = 0; i < n; i++) {
				mec[i] = true;
				double[] allocation = allocation(players, mec, capacity, cap);
				Player player = players.get(i);
				double utility = mecUtility(player.task, player.mecRate, allocation[i]);
				if (utility < player.initialUtility)
					mec[i] = false;
			}
			changed = !Arrays.equals(before, mec);
		}
		double[] allocation = allocation(players, mec, capacity, cap);
		double[] utility = new double[n];
		for (int i = 0; i < n; i++) {
			Player player = players.get(i);
			utility[i] = mec[i] ? mecUtility(player.task, player.mecRate, allocation[i]) : player.initialUtility;
		}
		return new Outcome(mec, allocation, utility, iterations);
	}

	/** @return F_n for every player, 0 for those that do not offload to the controller */
	private double[] allocation(List<Player> players, boolean[] mec, double capacity, double cap) {
		List<Task> tasks = new ArrayList<>();
		List<Double> rates = new ArrayList<>();
		for (int i = 0; i < mec.length; i++)
			if (mec[i]) {
				tasks.add(players.get(i).task);
				rates.add(players.get(i).mecRate);
			}
		double[] rateArray = rates.stream().mapToDouble(Double::doubleValue).toArray();
		double[] some = allocate(tasks, rateArray, capacity, cap);
		double[] all = new double[mec.length];
		for (int i = 0, k = 0; i < mec.length; i++)
			if (mec[i])
				all[i] = some[k++];
		return all;
	}

	// ----------------------------------------------------------------- VFC

	/** Eq. 15, with the wait at each fog node; a fog node with no share takes no time. */
	double fogDelay(Task task, List<Fog> fogs, double[] shares) {
		double delay = 0;
		for (int j = 0; j < fogs.size(); j++) {
			if (shares[j] <= 0)
				continue;
			Fog fog = fogs.get(j);
			if (fog.rate <= 0 || fog.speed <= 0)
				return Double.POSITIVE_INFINITY;
			delay = Math.max(delay,
					shares[j] * task.bits / fog.rate + fog.wait + shares[j] * task.work / fog.speed);
		}
		return delay;
	}

	/** Eq. 16. */
	double fogEnergy(Task task, List<Fog> fogs, double[] shares) {
		double energy = 0;
		for (int j = 0; j < fogs.size(); j++)
			if (shares[j] > 0)
				energy += config.transmitPower * shares[j] * task.bits / fogs.get(j).rate;
		return energy;
	}

	double fogUtility(Task task, List<Fog> fogs, double[] shares) {
		if (fogs.isEmpty())
			return infeasible(Double.POSITIVE_INFINITY);
		return utility(task, fogDelay(task, fogs, shares), fogEnergy(task, fogs, shares), 0);
	}

	/** Eq. 33: the delay of the whole task on one fog node, with its wait. */
	static double preference(Task task, Fog fog) {
		if (fog.rate <= 0 || fog.speed <= 0)
			return Double.POSITIVE_INFINITY;
		return task.bits / fog.rate + task.work / fog.speed + fog.wait;
	}

	/**
	 * Algorithm 3: the fog nodes a task is divided over, the {@code most} it
	 * prefers.
	 *
	 * @return the indices of the chosen nodes, best first
	 */
	static List<Integer> select(Task task, List<Fog> fogs, int most) {
		List<Integer> order = new ArrayList<>();
		for (int j = 0; j < fogs.size(); j++)
			if (Double.isFinite(preference(task, fogs.get(j))))
				order.add(j);
		order.sort(Comparator.comparingDouble(j -> preference(task, fogs.get(j))));
		return order.size() <= most ? order : new ArrayList<>(order.subList(0, most));
	}

	/**
	 * Algorithm 4: divides a task among the chosen fog nodes with a genetic
	 * algorithm that maximises its utility (problem P2'').
	 *
	 * @param task   the task
	 * @param fogs   the chosen fog nodes
	 * @param random the source of the algorithm
	 * @return lambda: the share of each, adding up to one
	 */
	double[] divide(Task task, List<Fog> fogs, Random random) {
		int k = fogs.size();
		if (k == 1)
			return new double[] { 1 };
		int size = config.population;
		double[][] population = new double[size][];
		for (int l = 0; l < size; l++) {
			population[l] = new double[k];
			for (int j = 0; j < k; j++)
				population[l][j] = random.nextDouble();
			normalize(population[l]);
		}
		double[] best = null;
		double bestFitness = Double.NEGATIVE_INFINITY;
		for (int g = 0; g < config.generations; g++) {
			double[] fitness = new double[size];
			int elite = 0;
			for (int l = 0; l < size; l++) {
				fitness[l] = fogUtility(task, fogs, population[l]);
				if (fitness[l] > fitness[elite])
					elite = l;
			}
			if (fitness[elite] > bestFitness) {
				bestFitness = fitness[elite];
				best = population[elite].clone();
			}
			// Elite-reserved 2-tournament selection.
			double[][] parents = new double[size][];
			for (int l = 0; l < size; l++) {
				int a = random.nextInt(size);
				int b = random.nextInt(size);
				parents[l] = population[fitness[a] >= fitness[b] ? a : b];
			}
			// Arithmetic crossover of random pairs (eq. 36).
			double[][] offspring = new double[size][];
			for (int l = 0; l < size; l += 2) {
				double[] x1 = parents[random.nextInt(size)];
				double[] x2 = parents[random.nextInt(size)];
				double[] y1 = x1.clone();
				double[] y2 = x2.clone();
				if (random.nextDouble() < config.crossover) {
					double tau = random.nextDouble();
					for (int j = 0; j < k; j++) {
						y1[j] = tau * x1[j] + (1 - tau) * x2[j];
						y2[j] = tau * x2[j] + (1 - tau) * x1[j];
					}
				}
				offspring[l] = y1;
				if (l + 1 < size)
					offspring[l + 1] = y2;
			}
			// Uniform mutation (eq. 37), then normalisation (eq. 35).
			for (double[] child : offspring) {
				for (int j = 0; j < k; j++)
					if (random.nextDouble() < config.mutation)
						child[j] = random.nextDouble();
				normalize(child);
			}
			// The elite replaces the worst offspring.
			int worst = 0;
			double worstFitness = Double.POSITIVE_INFINITY;
			for (int l = 0; l < size; l++) {
				double f = fogUtility(task, fogs, offspring[l]);
				if (f < worstFitness) {
					worstFitness = f;
					worst = l;
				}
			}
			offspring[worst] = best.clone();
			population = offspring;
		}
		for (double[] individual : population) {
			double f = fogUtility(task, fogs, individual);
			if (f > bestFitness) {
				bestFitness = f;
				best = individual.clone();
			}
		}
		return best;
	}

	/** Eq. 35, keeping every gene positive. */
	static void normalize(double[] genes) {
		double total = 0;
		for (int j = 0; j < genes.length; j++) {
			genes[j] = Math.max(genes[j], 1e-9);
			total += genes[j];
		}
		for (int j = 0; j < genes.length; j++)
			genes[j] /= total;
	}

	// ------------------------------------------------------------- channel

	/**
	 * Eq. 6 to 9: the rate between a device and a node on the ground, from their
	 * horizontal distance.
	 */
	double groundRate(double horizontalDistance) {
		double h = config.deviceAltitude;
		double d = Math.max(Math.hypot(horizontalDistance, h), 1);
		double elevation = Math.toDegrees(Math.asin(Math.min(1, h / d)));
		double los = 1 / (1 + config.losA * Math.exp(-config.losB * (elevation - config.losA)));
		double loss = config.pathLossReference * Math.pow(d, -config.pathLossExponent);
		double gain = los * loss + (1 - los) * config.nlosAttenuation * loss;
		double psi2 = config.halfBeamwidth * config.halfBeamwidth;
		double b = config.subchannelBandwidth;
		return b * log2(1 + config.transmitPower * gain * config.antennaGain / (psi2 * config.noiseDensity * b));
	}

	/** Eq. 10: the rate between a device and the controller in the air, over all its sub-channels. */
	double airRate(double horizontalDistance) {
		double dz = config.controllerAltitude - config.deviceAltitude;
		double d = Math.max(Math.hypot(horizontalDistance, dz), 1);
		double psi2 = config.halfBeamwidth * config.halfBeamwidth;
		double b = config.subchannels * config.subchannelBandwidth;
		return b * log2(1 + config.transmitPower * config.pathLossReference * config.antennaGain / (d * d)
				/ (psi2 * config.noiseDensity * b));
	}

	private static double log2(double x) {
		return Math.log(x) / Math.log(2);
	}
}
