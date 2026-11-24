package zip.hedlund.pureedgesim.edgesdk.algorithm.policies;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** The optimisation steps of MVTORA against their closed forms and optimality conditions. */
class MvtoraSolverTest {

	private static final Mvtora.Params P = Mvtora.Params.defaults();

	@Test
	void utilityIsTheLogRevenueMinusCostsAndRejectsLateTasks() {
		double u = MvtoraSolver.utility(P, 2, 0.5, 3, 10_000);
		assertEquals(0.9 * Math.log(1 + 2 - 0.5) - 0.1 * 3 - 1e-6 * 10_000, u, 1e-12);
		assertEquals(Double.NEGATIVE_INFINITY, MvtoraSolver.utility(P, 2, 2.01, 0, 0));
	}

	@Test
	void optimalMipsSetsTheMarginalUtilityToThePrice() {
		// d/dF [α log(A − w/F)] = α w / (F² (A − w/F)) must equal the price at Eq. 30
		double alpha = 0.9;
		double price = 1e-5;
		double w = 4_000;
		double a = 2.5;
		double f = MvtoraSolver.optimalMips(alpha, price, w, a, Double.POSITIVE_INFINITY);
		assertEquals(price, alpha * w / (f * f * (a - w / f)), price * 1e-9);
		assertEquals(500, MvtoraSolver.optimalMips(alpha, price, w, a, 500));
		assertEquals(500, MvtoraSolver.optimalMips(alpha, 0, w, a, 500));
	}

	@Test
	void bisectionFillsTheServerWithEqualMarginalUtilities() {
		double alpha = 0.9;
		double price = 1e-6;
		double[] work = { 4_000, 1_000, 8_000 };
		double[] room = { 2.5, 1.5, 3.0 };
		double total = 9_000;
		double[] f = MvtoraSolver.allocate(alpha, price, work, room, Double.POSITIVE_INFINITY, total, 1e-12);
		double sum = f[0] + f[1] + f[2];
		assertTrue(sum <= total + 1e-6 && sum > total * 0.999, "sum " + sum);
		// every task gets the same marginal utility, ρ₀ + γ (KKT)
		double m0 = alpha * work[0] / (f[0] * f[0] * (room[0] - work[0] / f[0]));
		for (int i = 1; i < f.length; i++)
			assertEquals(m0, alpha * work[i] / (f[i] * f[i] * (room[i] - work[i] / f[i])), m0 * 1e-6);
	}

	@Test
	void bisectionLeavesAnUnloadedServerAtTheUnconstrainedOptimumCappedAtOneCore() {
		double[] f = MvtoraSolver.allocate(0.9, 1e-6, new double[] { 100, 4_000 }, new double[] { 2, 2 }, 25_000,
				400_000, 1e-9);
		// the small task is worth about 6 700 MIPS at the base price; the large one is capped at a core
		assertEquals(MvtoraSolver.optimalMips(0.9, 1e-6, 100, 2, 25_000), f[0], 1e-9);
		assertTrue(f[0] < 25_000);
		assertEquals(25_000, f[1]);
	}

	@Test
	void anOverloadedServerIsScaledDownToItsCapacity() {
		// each task needs at least w / A = 2 000 MIPS, four of them do not fit in 4 000
		double[] f = MvtoraSolver.allocate(0.9, 1e-6, new double[] { 2_000, 2_000, 2_000, 2_000 },
				new double[] { 1, 1, 1, 1 }, 25_000, 4_000, 1e-9);
		assertEquals(4_000, f[0] + f[1] + f[2] + f[3], 1e-6);
	}

	@Test
	void vehicleSelectionKeepsTheKSmallestPreferences() {
		assertArrayEquals(new int[] { 2, 0 }, MvtoraSolver.selectVehicles(new double[] { 0.3, 0.9, 0.1, 0.5 }, 2));
		assertArrayEquals(new int[] { 1, 0 }, MvtoraSolver.selectVehicles(new double[] { 0.4, 0.2 }, 5));
	}

	@Test
	void geneticDivisionBalancesTheFinishingTimes() {
		// vehicle 0 is twice as fast: the optimum gives it 2/3 and both finish together
		double[] perShare = { 0.5, 1.0 };
		double[] fixed = { 0, 0 };
		double[] lambda = MvtoraSolver.divide(P, 2, perShare, fixed, new double[] { 0, 0 }, new Random(1));
		assertEquals(1, lambda[0] + lambda[1], 1e-9);
		assertEquals(2.0 / 3, lambda[0], 0.02);
		assertEquals(1.0 / 3, MvtoraSolver.divisionDelay(lambda, perShare, fixed), 0.01);
	}

	@Test
	void geneticDivisionAvoidsAVehicleThatCannotMeetTheDeadline() {
		// vehicle 1 alone needs 10 s, vehicle 0 alone 1 s; any large share of vehicle 1 misses 2 s
		double[] lambda = MvtoraSolver.divide(P, 2, new double[] { 1, 10 }, new double[] { 0, 0 },
				new double[] { 0, 0 }, new Random(7));
		assertTrue(MvtoraSolver.divisionDelay(lambda, new double[] { 1, 10 }, new double[] { 0, 0 }) <= 1,
				"lambda " + lambda[0] + ", " + lambda[1]);
	}

	@Test
	void smallSharesAreGivenToTheOtherVehicles() {
		double[] lambda = MvtoraSolver.dropSmallShares(new double[] { 0.6, 0.01, 0.385, 0.005 }, 0.02);
		assertArrayEquals(new double[] { 0.6 / 0.985, 0, 0.385 / 0.985, 0 }, lambda, 1e-12);
		// the largest share stays even when every share is small
		assertArrayEquals(new double[] { 1, 0 }, MvtoraSolver.dropSmallShares(new double[] { 0.015, 0.01 }, 0.5),
				1e-12);
	}

	@Test
	void aSingleVehicleTakesTheWholeTask() {
		assertArrayEquals(new double[] { 1 },
				MvtoraSolver.divide(P, 2, new double[] { 1 }, new double[] { 0 }, new double[] { 0 }, new Random()));
	}
}
