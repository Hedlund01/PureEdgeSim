package zip.hedlund.pureedgesim.edgesdk.algorithm;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Position;

/**
 * What a node knows about itself: its position (GPS), its load and its battery.
 *
 * @param node           the node
 * @param time           the clock
 * @param position       current position
 * @param cores          number of cores
 * @param busyCores      cores running a task
 * @param queuedTasks    tasks waiting for a core
 * @param batteryPercent battery level, or NaN on mains power
 */
public record LocalView(NodeRef node, double time, Position position, int cores, int busyCores, int queuedTasks,
		double batteryPercent) {

	/** @return busy cores over cores, in [0, 1] */
	public double load() {
		return cores == 0 ? 1 : (double) busyCores / cores;
	}
}
