package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zip.hedlund.pureedgesim.edgesdk.api.Candidate;
import zip.hedlund.pureedgesim.edgesdk.api.RunResult;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.metrics.Metrics;
import zip.hedlund.pureedgesim.edgesdk.metrics.StorageRecord;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageWorkload;
import zip.hedlund.pureedgesim.edgesdk.storage.strategies.EdgeCache;
import zip.hedlund.pureedgesim.edgesdk.storage.strategies.EventualGossip;
import zip.hedlund.pureedgesim.edgesdk.storage.strategies.PrimaryBackup;
import zip.hedlund.pureedgesim.edgesdk.storage.strategies.Quorum;

@Tag("integration")
class StorageIT {

	private static Scenario scenario() {
		return TestScenarios.twoRegions(3).duration(150)
				.withStorageWorkload(StorageWorkload.zipf(50, 0.8).reads(20).writes(10).timeout(1))
				.withFaults(FaultScript.parse("40s partition a for 40s")).build();
	}

	private static double availabilityOfRegionADuringPartition(RunResult r) {
		List<StorageRecord> ops = r.data().storage().stream()
				.filter(s -> s.client().startsWith("pa-") && s.duringPartition()).toList();
		return 100.0 * ops.stream().filter(StorageRecord::ok).count() / ops.size();
	}

	@Test
	void quorumIsUnavailableOnTheMinoritySide(@TempDir Path out) {
		RunResult r = scenario().run(Candidate.storageOnly("quorum", () -> new Quorum(3, 2, 2)), out);
		assertTrue(r.value(Metrics.STORAGE_AVAILABILITY) > 70);
		assertTrue(availabilityOfRegionADuringPartition(r) < 10, "" + availabilityOfRegionADuringPartition(r));
		// r + w > n: no stale read
		assertEquals(0.0, r.value(Metrics.STALE_READS), 1e-9);
	}

	@Test
	void eventualGossipStaysAvailableAndConvergesAfterHeal(@TempDir Path out) {
		RunResult r = scenario().run(Candidate.storageOnly("gossip", EventualGossip::new), out);
		assertTrue(availabilityOfRegionADuringPartition(r) > 95, "" + availabilityOfRegionADuringPartition(r));
		double convergence = r.value(Metrics.STORAGE_CONVERGENCE);
		assertTrue(convergence >= 0 && convergence <= 30, "convergence " + convergence);
		// replicas disagreed during the partition
		assertTrue(r.data().timeline().stream().anyMatch(s -> s.partitionActive() && s.divergentKeys() > 0));
	}

	@Test
	void primaryBackupAndEdgeCacheRun(@TempDir Path out) {
		RunResult pb = scenario().run(Candidate.storageOnly("pb", () -> new PrimaryBackup(2)), out.resolve("pb"));
		RunResult cache = scenario().run(Candidate.storageOnly("cache", () -> new EdgeCache(5)),
				out.resolve("cache"));
		assertTrue(pb.value(Metrics.STORAGE_AVAILABILITY) > 50);
		assertTrue(cache.value(Metrics.STORAGE_AVAILABILITY) > 50);
		// the cache serves stale data instead of failing
		assertTrue(cache.value(Metrics.STALE_READS) > 0);
	}
}
