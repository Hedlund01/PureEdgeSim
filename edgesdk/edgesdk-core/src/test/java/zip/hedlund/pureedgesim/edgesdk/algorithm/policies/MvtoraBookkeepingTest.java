package zip.hedlund.pureedgesim.edgesdk.algorithm.policies;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.Mvtora.DeviceState;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.Mvtora.SlotInfo;
import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.Mvtora.Use;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/** How the E-UAV and the devices account for MEC tasks between slots. */
class MvtoraBookkeepingTest {

	private static final NodeRef SERVER = NodeRef.of("euav");
	private static final NodeRef CAR = NodeRef.of("car-0");

	private static Use use(long task, NodeRef node, double since, double until) {
		return new Use(task, node, 4_000, 2, since, until);
	}

	@Test
	void endedTasksExpireWhenTheServerHoldsNoMore() {
		Set<Use> uses = new LinkedHashSet<>(List.of(use(1, SERVER, 0, 1), use(2, SERVER, 0, 3), use(3, CAR, 0, 1)));
		Mvtora.expire(uses, SERVER, 1, 2, 1);
		assertEquals(Set.of(use(2, SERVER, 0, 3)), uses);
	}

	@Test
	void tasksTheServerStillHoldsAreKeptForAnotherSlot() {
		// predicted to end at 1 and 1.5, but at 2 the server still holds three tasks
		Set<Use> uses = new LinkedHashSet<>(
				List.of(use(1, SERVER, 0, 1), use(2, SERVER, 0, 1.5), use(3, SERVER, 1, 3), use(4, CAR, 0, 1)));
		Mvtora.expire(uses, SERVER, 3, 2, 1);
		assertEquals(Set.of(use(1, SERVER, 0, 3), use(2, SERVER, 0, 3), use(3, SERVER, 1, 3)), uses);
		// the car's part is not the server's queue: it ends at its prediction
	}

	@Test
	void onlyTheLatestEndingOnesAreKeptUpToWhatTheServerHolds() {
		Set<Use> uses = new LinkedHashSet<>(List.of(use(1, SERVER, 0, 1), use(2, SERVER, 0, 1.5)));
		Mvtora.expire(uses, SERVER, 1, 2, 1);
		assertEquals(Set.of(use(2, SERVER, 0, 3)), uses);
	}

	@Test
	void devicesCountHeldAndExpectedTasksTheSlotDoesNotList() {
		// the server listed one task but holds three, and received 4 MEC tasks per second
		SlotInfo info = new SlotInfo(10, List.of(), List.of(use(1, SERVER, 9.5, 11)), 3, 4);
		DeviceState d = new DeviceState();
		assertEquals(2, Mvtora.unseenMecTasks(info, d, SERVER, 10));
		// half a slot later: two more expected from everyone
		assertEquals(4, Mvtora.unseenMecTasks(info, d, SERVER, 10.5));
		// one of them was this device's own, which it already counts
		d.sent.add(use(7, SERVER, 10.2, 11));
		d.sent.add(use(8, CAR, 10.3, 11));
		assertEquals(3, Mvtora.unseenMecTasks(info, d, SERVER, 10.5));
	}

	@Test
	void anIdleServerWithNoArrivalsHidesNothing() {
		SlotInfo info = new SlotInfo(10, List.of(), List.of(), 0, 0);
		assertEquals(0, Mvtora.unseenMecTasks(info, new DeviceState(), SERVER, 10.9));
	}
}
