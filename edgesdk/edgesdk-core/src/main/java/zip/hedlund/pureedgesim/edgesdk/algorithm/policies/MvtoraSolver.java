package zip.hedlund.pureedgesim.edgesdk.algorithm.policies;

import java.util.Arrays;
import java.util.Random;

/**
 * The optimisation steps of {@link Mvtora}, free of the SDK: the utility
 * (Eq. 21), MEC resource allocation by bisection (Algorithm 2, Eq. 30), vehicle
 * fog node selection (Algorithm 3, Eq. 34) and task division by a genetic
 * algorithm (Algorithm 4, Eqs. 37–40).
 * <p>
 * Units: times in seconds, work in million instructions, computing resources
 * in MIPS, energy in joules.
 */
final class MvtoraSolver {

	private MvtoraSolver() {
	}

	/**
	 * The utility of a task completed in {@code delay} seconds (Eq. 21):
	 * {@code α log(β + Tmax − T) − βₙ E − ρ₀ F}.
	 *
	 * @return the utility, or negative infinity when {@code delay > tMax}
	 *         (constraint 22j)
	 */
	static double utility(Mvtora.Params p, double tMax, double delay, double joules, double mips) {
		if (!(delay <= tMax))
			return Double.NEGATIVE_INFINITY;
		return p.alpha() * Math.log(p.beta() + tMax - delay) - p.energyWeight() * joules - p.pricePerMips() * mips;
	}

	/**
	 * Eq. 30 for one task: the MEC resource that maximises its utility at the
	 * resource price {@code ρ₀ + γ}, clamped to {@code [0, cap]}.
	 *
	 * @param work     ηₙDₙ, million instructions
	 * @param headroom {@code β + Tmax − (transfer time)}, seconds; positive
	 */
	static double optimalMips(double alpha, double price, double work, double headroom, double cap) {
		if (!(price > 0))
			return cap;
		double f = (work + Math.sqrt(work * work + 4 * headroom * alpha * work / price)) / (2 * headroom);
		return Math.min(cap, Math.max(0, f));
	}

	/**
	 * Algorithm 2: shares the MEC server's resource among the tasks offloaded to
	 * it by bisection on the Lagrange multiplier γ of {@code Σ Fₙ ≤ Fmax}.
	 * <p>
	 * A task occupies one core in the simulator, so each {@code Fₙ} is also capped
	 * at {@code cap} (one core). When even an infinite price cannot fit the tasks
	 * (each needs at least {@code work / headroom} to meet its deadline), the
	 * shares are scaled down to fit.
	 *
	 * @param work     ηₙDₙ per task
	 * @param headroom {@code β + Tmax − (transfer time)} per task, positive
	 * @param cap      the most one task can get
	 * @param total    {@code Fmax}
	 * @param accuracy the search stops when {@code γmax − γmin < accuracy · γmax}
	 */
	static double[] allocate(double alpha, double price, double[] work, double[] headroom, double cap, double total,
			double accuracy) {
		double[] f = shares(alpha, price, 0, work, headroom, cap);
		if (sum(f) <= total)
			return f;
		double lo = 0;
		double hi = Math.max(price, 1e-12);
		for (int i = 0; i < 200 && sum(shares(alpha, price, hi, work, headroom, cap)) > total; i++)
			hi *= 2;
		for (int i = 0; i < 200 && hi - lo >= accuracy * hi; i++) {
			double mid = (lo + hi) / 2;
			if (sum(shares(alpha, price, mid, work, headroom, cap)) >= total)
				lo = mid;
			else
				hi = mid;
		}
		f = shares(alpha, price, hi, work, headroom, cap);
		double s = sum(f);
		if (s > total)
			for (int i = 0; i < f.length; i++)
				f[i] *= total / s;
		return f;
	}

	private static double[] shares(double alpha, double price, double gamma, double[] work, double[] headroom,
			double cap) {
		double[] f = new double[work.length];
		for (int i = 0; i < f.length; i++)
			f[i] = optimalMips(alpha, price + gamma, work[i], headroom[i], cap);
		return f;
	}

	private static double sum(double[] v) {
		double s = 0;
		for (double x : v)
			s += x;
		return s;
	}

	/**
	 * Algorithm 3: the {@code k} candidates with the smallest preference value
	 * {@code Pr(n, j)} (Eq. 34), which by Theorem 3 minimise the completion delay.
	 *
	 * @return indices into {@code preference}, best first
	 */
	static int[] selectVehicles(double[] preference, int k) {
		Integer[] order = new Integer[preference.length];
		for (int i = 0; i < order.length; i++)
			order[i] = i;
		Arrays.sort(order, (a, b) -> Double.compare(preference[a], preference[b]));
		int[] out = new int[Math.min(k, order.length)];
		for (int i = 0; i < out.length; i++)
			out[i] = order[i];
		return out;
	}

	/**
	 * The delay of a division (Eq. 15): the slowest part, where part {@code j}
	 * takes {@code λⱼ · perShare[j] + fixed[j]} seconds.
	 */
	static double divisionDelay(double[] lambda, double[] perShare, double[] fixed) {
		double t = 0;
		for (int j = 0; j < lambda.length; j++)
			if (lambda[j] > 0)
				t = Math.max(t, lambda[j] * perShare[j] + fixed[j]);
		return t;
	}

	/**
	 * The objective of P2'' (Eq. 37) for one division. An infeasible division
	 * (delay over {@code tMax}) ranks below every feasible one, faster first.
	 */
	static double divisionFitness(Mvtora.Params p, double tMax, double[] lambda, double[] perShare, double[] fixed,
			double[] joulesPerShare) {
		double delay = divisionDelay(lambda, perShare, fixed);
		double joules = 0;
		for (int j = 0; j < lambda.length; j++)
			joules += lambda[j] * joulesPerShare[j];
		double u = utility(p, tMax, delay, joules, 0);
		return u == Double.NEGATIVE_INFINITY ? -1e9 - delay : u;
	}

	/**
	 * Algorithm 4: divides a task among the selected vehicles with a real-coded
	 * genetic algorithm: elite-reserved 2-tournament selection, arithmetic
	 * crossover (Eq. 39), random-reset mutation of gene pairs (Eq. 40), and
	 * normalisation to {@code Σ λⱼ = 1} (Eq. 38) after each generation.
	 *
	 * @return the best division found, summing to 1
	 */
	static double[] divide(Mvtora.Params p, double tMax, double[] perShare, double[] fixed, double[] joulesPerShare,
			Random rng) {
		int k = perShare.length;
		if (k == 1)
			return new double[] { 1 };
		int size = Math.max(2, p.population());
		double[][] pop = new double[size][];
		for (int l = 0; l < size; l++) {
			double[] x = new double[k];
			for (int j = 0; j < k; j++)
				x[j] = rng.nextDouble();
			pop[l] = normalise(x);
		}
		double[] fit = new double[size];
		double[] best = null;
		double bestFit = Double.NEGATIVE_INFINITY;
		for (int g = 0; g < p.generations(); g++) {
			int elite = 0;
			for (int l = 0; l < size; l++) {
				fit[l] = divisionFitness(p, tMax, pop[l], perShare, fixed, joulesPerShare);
				if (fit[l] > fit[elite])
					elite = l;
			}
			if (fit[elite] > bestFit) {
				bestFit = fit[elite];
				best = pop[elite].clone();
			}
			double[][] parents = new double[size][];
			for (int l = 0; l < size; l++) {
				int a = rng.nextInt(size);
				int b = rng.nextInt(size);
				parents[l] = fit[a] >= fit[b] ? pop[a] : pop[b];
			}
			double[][] next = new double[size][];
			for (int l = 0; l < size; l += 2) {
				double[] x1 = parents[l];
				double[] x2 = parents[(l + 1) % size];
				if (rng.nextDouble() < p.crossover()) {
					double tau = rng.nextDouble();
					double[] c1 = new double[k];
					double[] c2 = new double[k];
					for (int j = 0; j < k; j++) {
						c1[j] = tau * x1[j] + (1 - tau) * x2[j];
						c2[j] = tau * x2[j] + (1 - tau) * x1[j];
					}
					next[l] = c1;
					if (l + 1 < size)
						next[l + 1] = c2;
				} else {
					next[l] = x1.clone();
					if (l + 1 < size)
						next[l + 1] = x2.clone();
				}
			}
			int worst = 0;
			double worstFit = Double.POSITIVE_INFINITY;
			for (int l = 0; l < size; l++) {
				double[] x = next[l];
				for (int j = 0; j < k; j++)
					if (rng.nextDouble() < p.mutation()) {
						int other = (j + 1 + rng.nextInt(k - 1)) % k;
						x[j] = rng.nextDouble();
						x[other] = rng.nextDouble();
					}
				normalise(x);
				double f = divisionFitness(p, tMax, x, perShare, fixed, joulesPerShare);
				if (f < worstFit) {
					worstFit = f;
					worst = l;
				}
			}
			next[worst] = best.clone();
			pop = next;
		}
		for (double[] x : pop) {
			double f = divisionFitness(p, tMax, x, perShare, fixed, joulesPerShare);
			if (f > bestFit) {
				bestFit = f;
				best = x;
			}
		}
		return best.clone();
	}

	private static double[] normalise(double[] x) {
		double s = sum(x);
		for (int j = 0; j < x.length; j++)
			x[j] = s > 0 ? x[j] / s : 1.0 / x.length;
		return x;
	}
}
