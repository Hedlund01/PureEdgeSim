package zip.hedlund.pureedgesim.edgesdk.examples;

import java.util.Locale;

import zip.hedlund.pureedgesim.edgesdk.algorithm.policies.HeartbeatFallback;
import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultRecord;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultType;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.metrics.TaskRecord;
import zip.hedlund.pureedgesim.edgesdk.metrics.TimelineSample;

/**
 * User story 6: write a scripted partition scenario (in the text format) and
 * check how an algorithm behaves before, during and after healing.
 */
public final class Story6ScriptedPartitionAndHeal {

	static final String SCRIPT = """
			# district 1 loses its uplinks for 90 s, then rsu-2 reboots
			120s partition district-1 for 90s
			260s crash rsu-2 recover 20s
			""";

	public static void main(String[] args) {
		var scenario = Story5CompareFaultModels.city().toBuilder().withFaults(FaultScript.parse(SCRIPT)).build();
		RunResult r = scenario.run(Candidate.of("heartbeat-fallback", HeartbeatFallback::new),
				Examples.output("story6"));

		FaultRecord p = r.data().faults().of(FaultType.PARTITION).get(0);
		System.out.printf(Locale.ROOT, "partition %.0f..%.0f s: %s%n", p.start(), p.end(), p.groups());
		var district = scenario.design().group("district-1").members();
		print(r, district, "before", t -> t.createdAt() < p.start());
		print(r, district, "during", t -> p.activeAt(t.createdAt()));
		print(r, district, "after ", t -> t.createdAt() >= p.end());
		System.out.printf(Locale.ROOT, "detection latency %.2f s, time to recover %.2f s%n",
				r.value(Metrics.DETECTION_LATENCY), r.value(Metrics.TIME_TO_RECOVER));
		System.out.println("time  links-down  success-in-window");
		for (TimelineSample s : r.data().timeline())
			System.out.printf(Locale.ROOT, "%4.0f  %10d  %s%n", s.time(), s.linksDown(),
					Double.isNaN(s.successPct()) ? "-" : String.format(Locale.ROOT, "%.0f %%", s.successPct()));
	}

	private static void print(RunResult r, java.util.Set<String> district, String label,
			java.util.function.Predicate<TaskRecord> when) {
		var tasks = r.data().tasks().stream().filter(t -> district.contains(t.device())).filter(when).toList();
		long ok = tasks.stream().filter(t -> t.outcome().succeeded()).count();
		System.out.printf(Locale.ROOT, "district-1 %s: %d/%d tasks succeeded%n", label, ok, tasks.size());
	}
}
