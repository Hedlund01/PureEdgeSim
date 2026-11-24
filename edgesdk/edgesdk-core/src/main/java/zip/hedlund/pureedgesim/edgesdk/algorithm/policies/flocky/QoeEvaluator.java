package zip.hedlund.pureedgesim.edgesdk.algorithm.policies.flocky;

/**
 * Ranks the eligible deployments of a component: the higher the score, the
 * earlier Flocky asks that node to deploy. Flocky's two built-in evaluators are
 * {@link #LEGACY} (latency only) and {@link #scored} (a static weighted score
 * over metadata).
 */
@FunctionalInterface
public interface QoeEvaluator {

	/**
	 * A node that could run a definition of a component, as the requesting node
	 * knows it.
	 *
	 * @param component  the component asked for
	 * @param definition the implementation
	 * @param node       the node's summary, as last fetched
	 * @param rtt        the round-trip time to the node measured by discovery, seconds
	 * @param maxRtt     the discovery range at the requesting node, seconds
	 */
	record Option(Component component, ComponentDefinition definition, NodeSummary node, double rtt, double maxRtt) {
	}

	/** @return the score of an option; higher is better */
	double score(Option option);

	/** Ranks by latency only. */
	QoeEvaluator LEGACY = o -> -o.rtt();

	/** {@link #scored(double, double, double, double)} with weights 1, 1, 0.5 and 1. */
	static QoeEvaluator scored() {
		return scored(1, 1, 0.5, 1);
	}

	/**
	 * Flocky's "Scored" evaluator: a weighted sum of terms in [0, 1].
	 *
	 * @param latency  weight of closeness, {@code 1 - rtt / maxRtt}
	 * @param cpu      weight of the share of cores that stay free, and that are idle
	 * @param ram      weight of the share of RAM that stays free
	 * @param optional weight of the share of the component's optional traits met
	 */
	static QoeEvaluator scored(double latency, double cpu, double ram, double optional) {
		return o -> {
			NodeSummary n = o.node();
			ComponentDefinition d = o.definition();
			boolean reuse = n.hasDeployed(d);
			double close = o.maxRtt() > 0 ? Math.max(0, 1 - o.rtt() / o.maxRtt()) : 0;
			double freeCpu = n.cores() > 0 ? (n.freeCores() - (reuse ? 0 : d.cores())) / n.cores() : 0;
			double freeRam = n.ramMb() > 0 ? (n.freeRamMb() - (reuse ? 0 : d.ramMb())) / n.ramMb() : 0;
			double met = 1;
			if (!o.component().optional().isEmpty()) {
				long count = o.component().optional().stream()
						.filter(t -> n.traits().contains(t) || d.traits().contains(t)).count();
				met = (double) count / o.component().optional().size();
			}
			return latency * close + cpu * 0.5 * (Math.max(0, freeCpu) + (1 - n.load())) + ram * Math.max(0, freeRam)
					+ optional * met;
		};
	}
}
