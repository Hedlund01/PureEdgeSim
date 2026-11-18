package partitiontestbed.baselines;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

import partitiontestbed.Attempt;
import partitiontestbed.EdgeServer;
import partitiontestbed.Job;
import partitiontestbed.Service;
import partitiontestbed.Testbed;

/**
 * Runs every task on several hosts at once: on the server it arrives at, as
 * {@link AccessServerStrategy} does, and on {@code baselines.replicated.copies}
 * minus one of that server's mesh neighbours. The first result to reach the
 * device counts; the others are duplicates, which cost compute and bandwidth. A
 * neighbour that gets a copy and runs no instance of the service starts one, so
 * the first copies wait for it.
 * <p>
 * It trades capacity for resilience to a host that fails or is cut off while it
 * runs a task. It chooses neighbours from the configuration alone: whether a
 * neighbour can be reached is not known when the copy is sent.
 */
public class ReplicatedStrategy extends AccessServerStrategy {

	/** The name it is registered under. */
	public static final String NAME = "REPLICATED";

	private BaselineConfig config;
	private int copiesSent;

	@Override
	public void initialize() {
		config = new BaselineConfig(Testbed.settingsFile("baselines.properties"));
	}

	@Override
	public void onTaskArrived(ComputingNode node, Job job) {
		runHere(node, job);
		for (EdgeServer backup : backups(node, job.service(), config.copies - 1)) {
			copiesSent++;
			ctx.dispatch(job, node, backup.node);
		}
	}

	/** @return up to {@code count} mesh neighbours of the node that can run the service */
	private List<EdgeServer> backups(ComputingNode node, Service service, int count) {
		List<EdgeServer> backups = new ArrayList<>();
		EdgeServer server = ctx.serverOf(node);
		if (server == null || count <= 0)
			return backups;
		for (EdgeServer neighbour : ctx.meshNeighbours(server))
			if (backups.size() < count && service.fits(neighbour.traits))
				backups.add(neighbour);
		return backups;
	}

	@Override
	public void onNoInstance(ComputingNode host, Attempt attempt) {
		if (ctx.startInstance(host, attempt.job().service(), null))
			ctx.dispatch(attempt.job(), host, host);
	}

	@Override
	public List<String> reportLines() {
		return List.of(String.format(Locale.ROOT, "Replicated: %d copies sent to neighbours, %d hosts per task",
				copiesSent, config.copies));
	}
}
