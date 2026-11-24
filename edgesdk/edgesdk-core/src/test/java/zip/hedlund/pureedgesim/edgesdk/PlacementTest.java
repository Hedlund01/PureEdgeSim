package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

class PlacementTest {

	private static final NodeRef A = NodeRef.of("a");
	private static final NodeRef B = NodeRef.of("b");
	private static final NodeRef C = NodeRef.of("c");

	@Test
	void aSplitIsNormalisedAndDropsZeroShares() {
		Map<NodeRef, Double> shares = new LinkedHashMap<>();
		shares.put(A, 3.0);
		shares.put(B, 0.0);
		shares.put(C, 1.0);
		Placement p = Placement.split(shares).withRetryAfter(2);
		assertTrue(p.isSplit());
		assertFalse(p.isLocal());
		assertTrue(p.node().isEmpty());
		assertEquals(Map.of(A, 0.75, C, 0.25), p.shares());
		assertEquals(2, p.retryAfter());
	}

	@Test
	void aSplitOverOneNodeIsAnOffload() {
		Placement p = Placement.split(Map.of(A, 0.4, B, 0.0));
		assertFalse(p.isSplit());
		assertEquals(A, p.node().orElseThrow());
	}

	@Test
	void aSplitNeedsValidShares() {
		assertThrows(IllegalArgumentException.class, () -> Placement.split(Map.of()));
		assertThrows(IllegalArgumentException.class, () -> Placement.split(Map.of(A, 0.0)));
		assertThrows(IllegalArgumentException.class, () -> Placement.split(Map.of(A, -1.0, B, 2.0)));
		assertThrows(IllegalArgumentException.class, () -> Placement.split(Map.of(A, Double.NaN)));
	}
}
