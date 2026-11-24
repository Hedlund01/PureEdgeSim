package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import zip.hedlund.pureedgesim.edgesdk.detect.TimeoutTracker;
import zip.hedlund.pureedgesim.edgesdk.metrics.Direction;
import zip.hedlund.pureedgesim.edgesdk.metrics.Stats;
import zip.hedlund.pureedgesim.edgesdk.storage.VectorClock;

class MetricsTest {

	@Test
	void statistics() {
		double[] v = { 1, 2, 3, 4 };
		assertEquals(2.5, Stats.mean(v));
		assertEquals(Math.sqrt(5.0 / 3), Stats.std(v), 1e-12);
		assertEquals(3.182 * Stats.std(v) / 2, Stats.ci95(v), 1e-9);
		assertEquals(2.5, Stats.percentile(v, 50));
		assertEquals(4, Stats.percentile(v, 100));
		assertTrue(Double.isNaN(Stats.ci95(new double[] { 1 })));
		assertTrue(Double.isNaN(Stats.pct(1, 0)));
	}

	@Test
	void ci95UsesStudentTBeyondThirtyReplications() {
		// exact two-sided 95 % t quantiles for 31, 40, 60 and 120 degrees of freedom
		double[][] cases = { { 32, 2.039513 }, { 41, 2.021075 }, { 61, 2.000298 }, { 121, 1.979930 } };
		for (double[] c : cases) {
			double[] v = new double[(int) c[0]];
			for (int i = 0; i < v.length; i++)
				v[i] = i % 7;
			double se = Stats.std(v) / Math.sqrt(v.length);
			assertEquals(c[1], Stats.ci95(v) / se, 1e-5, "n = " + v.length);
		}
	}

	@Test
	void directions() {
		assertTrue(Direction.HIGHER_IS_BETTER.better(2, 1));
		assertTrue(Direction.LOWER_IS_BETTER.better(1, 2));
		assertTrue(Direction.LOWER_IS_BETTER.better(1, Double.NaN));
		assertTrue(!Direction.LOWER_IS_BETTER.better(Double.NaN, 1));
	}

	@Test
	void vectorClocks() {
		VectorClock a = VectorClock.EMPTY.increment("x");
		VectorClock b = a.increment("y");
		VectorClock c = a.increment("z");
		assertEquals(VectorClock.Order.BEFORE, a.compare(b));
		assertEquals(VectorClock.Order.AFTER, b.compare(a));
		assertEquals(VectorClock.Order.CONCURRENT, b.compare(c));
		assertEquals(VectorClock.Order.AFTER, b.merge(c).compare(c));
	}

	@Test
	void adaptiveTimeout() {
		TimeoutTracker t = new TimeoutTracker(0.01, 2);
		assertEquals(2, t.timeout());
		for (int i = 0; i < 20; i++)
			t.observe(0.05);
		assertTrue(t.timeout() < 0.2 && t.timeout() >= 0.05, "timeout " + t.timeout());
	}
}
