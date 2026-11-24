package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zip.hedlund.pureedgesim.edgesdk.algorithm.Algorithm;
import zip.hedlund.pureedgesim.edgesdk.algorithm.NodeLocal;
import zip.hedlund.pureedgesim.edgesdk.algorithm.OrchestrationPolicy;
import zip.hedlund.pureedgesim.edgesdk.algorithm.Placement;
import zip.hedlund.pureedgesim.edgesdk.algorithm.SdkContext;
import zip.hedlund.pureedgesim.edgesdk.algorithm.TaskRequest;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

@Tag("integration")
class InformationBoundaryIT {

	/** Records everything the engine tells it. */
	public static class Probe extends OrchestrationPolicy {
		static final List<String> calls = new ArrayList<>();
		static int sent;
		static int received;
		static boolean lateTimerFired;
		static Integer valueAfterRestart;
		NodeLocal<int[]> local;

		@Override
		public void onStart(SdkContext ctx) {
			NodeRef device = NodeRef.of("pa-0");
			NodeRef cloud = NodeRef.of("cloud");
			NodeRef edge = NodeRef.of("edge-a");
			local = ctx.nodeLocal(n -> new int[1]);
			ctx.every(device, 1.0, () -> {
				sent++;
				ctx.send(device, cloud, 100, "ping");
			});
			ctx.schedule(edge, 40, () -> local.at(edge)[0] = 5);
			// would fire at 55, but edge-a crashes at 50
			ctx.schedule(edge, 45, () -> ctx.schedule(edge, 10, () -> lateTimerFired = true));
		}

		@Override
		public Placement place(NodeRef device, TaskRequest task) {
			return Placement.local();
		}

		@Override
		public void onMessage(NodeRef at, NodeRef from, Object payload) {
			received++;
			calls.add("message");
		}

		@Override
		public void onNodeRestarted(NodeRef at) {
			calls.add("restarted " + at);
			valueAfterRestart = local.at(at)[0];
		}
	}

	@Test
	void algorithmsLearnNothingAboutFaults(@TempDir Path out) {
		Probe.calls.clear();
		Probe.sent = 0;
		Probe.received = 0;
		Probe.lateTimerFired = false;
		Probe.valueAfterRestart = null;
		RunResult r = TestScenarios.twoRegions(2)
				.withFaults(FaultScript.parse("20s partition a for 15s\n50s crash edge-a recover 5s")).build()
				.run(Candidate.of("probe", Probe::new), out);
		// messages were lost during the partition and the crash, silently
		assertTrue(Probe.sent > Probe.received + 10, Probe.sent + " sent, " + Probe.received + " received");
		assertTrue(Probe.calls.stream().allMatch(c -> c.equals("message") || c.equals("restarted edge-a")),
				Probe.calls.toString());
		// the only fault-related signal is local: edge-a knows it rebooted
		assertEquals(1, Probe.calls.stream().filter(c -> c.startsWith("restarted")).count());
		assertFalse(Probe.lateTimerFired, "a crash must cancel the node's timers");
		assertEquals(0, Probe.valueAfterRestart, "a crash must wipe NodeLocal state");
		assertTrue(r.data().faults().records().size() >= 2);
	}

	@Test
	void theApiExposesNoLiveState() {
		Set<String> forbidden = Set.of("Link", "SdkLink", "LiveNetwork", "FaultLog", "FaultRecord", "SdkNode",
				"SdkWorld", "NetworkEngine", "LinkState");
		for (Class<?> type : List.of(SdkContext.class, Algorithm.class, OrchestrationPolicy.class)) {
			for (Method m : type.getMethods()) {
				assertFalse(forbidden.contains(m.getReturnType().getSimpleName()), type + "." + m.getName());
				for (Class<?> p : m.getParameterTypes())
					assertFalse(forbidden.contains(p.getSimpleName()), type + "." + m.getName());
				String n = m.getName().toLowerCase();
				assertFalse(n.contains("partition") || n.contains("linkdown") || n.contains("reachable")
						|| n.contains("crashed"), "fault callback exposed: " + type + "." + m.getName());
			}
		}
	}
}
