package zip.hedlund.pureedgesim.edgesdk.metrics;

/**
 * The state of a run at one sampling instant; window figures cover the time since
 * the previous sample.
 *
 * @param time             sample time
 * @param linksDown        links down (ground truth)
 * @param nodesDown        crashed or dead nodes
 * @param partitionActive  a partition is active
 * @param tasksDone        tasks that succeeded in the window
 * @param tasksFailed      tasks whose deadline passed without a result in the window (any outcome
 *                         but success; a {@link TaskOutcome#LATE} task counts when its deadline passes)
 * @param successPct       success share of the tasks finished in the window, or NaN
 * @param divergentKeys    keys whose live replicas hold different versions
 * @param controlBytes     algorithm message bytes sent in the window
 */
public record TimelineSample(double time, int linksDown, int nodesDown, boolean partitionActive, int tasksDone,
		int tasksFailed, double successPct, int divergentKeys, long controlBytes) {
}
