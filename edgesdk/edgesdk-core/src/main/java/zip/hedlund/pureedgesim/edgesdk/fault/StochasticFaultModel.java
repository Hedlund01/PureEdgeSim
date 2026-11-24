package zip.hedlund.pureedgesim.edgesdk.fault;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.mechalikh.pureedgesim.distribution.AbstractDistribution;
import com.mechalikh.pureedgesim.distribution.Distribution;
import com.mechalikh.pureedgesim.distribution.ExponentialDistribution;

import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.LinkRef;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.network.Degradation;

/**
 * Random faults drawn from distributions: MTBF/MTTR per link or node, random
 * crash-recovery, per-interval failure probabilities, random degradations and
 * random partitions.
 * <p>
 * Every fault of the run is drawn at time 0 from the model's random stream (the
 * PureEdgeSim {@link Distribution}s are re-seeded from it), so the realisation is
 * the same for every algorithm compared.
 *
 * <pre>{@code
 * StochasticFaultModel.create()
 *     .linkFailures(Targets.links(), exp(600), exp(30))       // MTBF 600 s, MTTR 30 s
 *     .nodeCrashes(Targets.nodes(Tier.EDGE_STATIC), exp(1800), exp(60))
 *     .randomPartitions(List.of(Select.group("a"), Select.group("b")), exp(900), exp(60))
 * }</pre>
 */
public final class StochasticFaultModel extends FaultModel {

	private interface Entry {
		void install(FaultScheduler s, DesignTopology t, long rng);
	}

	private final List<Entry> entries;
	private final String label;

	private StochasticFaultModel(List<Entry> entries, String label) {
		this.entries = List.copyOf(entries);
		this.label = label;
	}

	/** An empty model; add fault processes with the methods below. */
	public static StochasticFaultModel create() {
		return new StochasticFaultModel(List.of(), "stochastic");
	}

	/** Exponential failures of every link, with the given mean times in seconds. */
	public static StochasticFaultModel links(double mtbf, double mttr) {
		return create().linkFailures(Targets.links(), exp(mtbf), exp(mttr));
	}

	/** @return an exponential distribution of the given mean */
	public static Distribution exp(double mean) {
		return new ExponentialDistribution(mean);
	}

	/** Each selected link alternates up times drawn from {@code ttf} and down times from {@code ttr}. */
	public StochasticFaultModel linkFailures(LinkSelect targets, Distribution ttf, Distribution ttr) {
		return with((s, t, rng) -> {
			for (LinkRef ref : targets.resolve(t)) {
				Random r = substream(rng, ref.toString());
				alternate(s, ttf, ttr, r, (at, d) -> s.linkDown(exact(ref), at, d));
			}
		});
	}

	/**
	 * Each link fails with probability {@code p} in every interval of
	 * {@code interval} seconds, and is repaired after a time drawn from {@code ttr}.
	 */
	public StochasticFaultModel perLinkProbability(LinkSelect targets, double p, double interval, Distribution ttr) {
		if (p <= 0 || p >= 1 || !(interval > 0))
			throw new IllegalArgumentException("need 0 < p < 1 and interval > 0");
		return with((s, t, rng) -> {
			for (LinkRef ref : targets.resolve(t)) {
				Random r = substream(rng, ref.toString());
				seed(ttr, r);
				double time = 0;
				while (true) {
					// geometric number of intervals until the next failure
					int k = 1 + (int) Math.floor(Math.log(1 - r.nextDouble()) / Math.log(1 - p));
					time += k * interval;
					if (time >= s.duration())
						break;
					double down = Math.max(1e-3, ttr.sample());
					s.linkDown(exact(ref), time, down);
					time += down;
				}
			}
		});
	}

	/** Random degradations of each selected link. */
	public StochasticFaultModel linkDegradations(LinkSelect targets, Distribution ttf, Distribution duration,
			Degradation degradation) {
		return with((s, t, rng) -> {
			for (LinkRef ref : targets.resolve(t)) {
				Random r = substream(rng, "deg:" + ref);
				alternate(s, ttf, duration, r, (at, d) -> s.degrade(exact(ref), at, d, degradation));
			}
		});
	}

	/**
	 * Crash-recovery of each selected node: up times from {@code ttf}, down times
	 * from {@code ttr}. With {@code ttr == null} the first crash is a crash-stop.
	 */
	public StochasticFaultModel nodeCrashes(Select targets, Distribution ttf, Distribution ttr) {
		return with((s, t, rng) -> {
			for (NodeRef node : targets.resolve(t)) {
				Random r = substream(rng, "node:" + node);
				Select one = exact(node);
				if (ttr == null) {
					seed(ttf, r);
					double at = Math.max(1e-3, ttf.sample());
					if (at < s.duration())
						s.crash(one, at, Recovery.never());
				} else {
					alternate(s, ttf, ttr, r, (at, d) -> s.crash(one, at, Recovery.after(d)));
				}
			}
		});
	}

	/** Partitions into the given groups, starting after {@code interArrival} and lasting {@code duration}. */
	public StochasticFaultModel randomPartitions(List<Select> groups, Distribution interArrival,
			Distribution duration) {
		List<Select> copy = List.copyOf(groups);
		return with((s, t, rng) -> alternate(s, interArrival, duration, substream(rng, "partition"),
				(at, d) -> s.partition(copy, at, d)));
	}

	/** @return this model with another label */
	public StochasticFaultModel named(String newLabel) {
		return new StochasticFaultModel(entries, newLabel);
	}

	@Override
	public void install(FaultScheduler scheduler, DesignTopology topology, Random rng) {
		long base = rng.nextLong();
		for (int i = 0; i < entries.size(); i++)
			entries.get(i).install(scheduler, topology, base + 0x632BE59BD9B4E019L * (i + 1));
	}

	@Override
	public String label() {
		return label;
	}

	private StochasticFaultModel with(Entry e) {
		List<Entry> copy = new ArrayList<>(entries);
		copy.add(e);
		return new StochasticFaultModel(copy, label);
	}

	private interface Inject {
		void apply(double at, double duration);
	}

	private static void alternate(FaultScheduler s, Distribution up, Distribution down, Random r, Inject inject) {
		seed(up, r);
		seed(down, r);
		double time = 0;
		while (true) {
			time += Math.max(1e-3, up.sample());
			if (time >= s.duration())
				return;
			double d = Math.max(1e-3, down.sample());
			inject.apply(time, d);
			time += d;
		}
	}

	private static void seed(Distribution d, Random r) {
		if (d instanceof AbstractDistribution ad)
			ad.setRandom(r);
	}

	/** A stream per target, so adding a target does not change the draws of the others. */
	private static Random substream(long base, String key) {
		return new Random(base ^ (key.hashCode() * 0x9E3779B97F4A7C15L));
	}

	private static LinkSelect exact(LinkRef ref) {
		return t -> Set.of(ref);
	}

	private static Select exact(NodeRef node) {
		return t -> Set.of(node);
	}
}
