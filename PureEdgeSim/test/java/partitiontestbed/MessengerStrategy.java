package partitiontestbed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;

import partitiontestbed.baselines.AccessServerStrategy;

/**
 * {@link AccessServerStrategy} that also sends, from the westernmost server to the
 * easternmost one, a message and an RPC during the partition of the test settings
 * (90 s to 180 s, west and east) and again after it, and records what arrives.
 */
final class MessengerStrategy extends AccessServerStrategy {

	static final String NAME = "MESSENGER";

	/** When each message arrived, by the time it was sent. */
	static final List<double[]> delivered = new ArrayList<>();
	/** The times of the RPCs that got a reply, and of those that timed out. */
	static final List<Double> replied = new ArrayList<>();
	static final List<Double> timedOut = new ArrayList<>();

	static void register() {
		Testbed.register(NAME, MessengerStrategy::new);
	}

	@Override
	public void initialize() {
		delivered.clear();
		replied.clear();
		timedOut.clear();
		List<EdgeServer> byX = new ArrayList<>(ctx.servers());
		byX.sort(Comparator.comparingDouble(server -> server.node.getMobilityModel().getCurrentLocation().getXPos()));
		ComputingNode west = byX.get(0).node;
		ComputingNode east = byX.get(byX.size() - 1).node;
		for (double at : new double[] { 120, 200 })
			ctx.schedule(at, () -> {
				double sent = ctx.now();
				ctx.send(west, east, 500, sent);
				ctx.rpc(west, east, 100, "ping", 2, reply -> replied.add(sent), () -> timedOut.add(sent));
			});
	}

	@Override
	public void onMessage(ComputingNode node, ComputingNode from, Object payload) {
		delivered.add(new double[] { (Double) payload, ctx.now() });
	}

	@Override
	public StrategyContext.Reply onRpc(ComputingNode node, ComputingNode from, Object request) {
		return new StrategyContext.Reply("pong", 100);
	}
}
