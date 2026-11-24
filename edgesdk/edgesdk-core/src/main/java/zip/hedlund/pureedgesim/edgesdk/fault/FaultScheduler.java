package zip.hedlund.pureedgesim.edgesdk.fault;

import java.util.List;

import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.network.Degradation;

/**
 * How a {@link FaultModel} injects faults. Times are absolute simulation times;
 * targets are resolved when the fault starts. Every fault is written to the
 * {@link FaultLog}.
 */
public interface FaultScheduler {

	/** Takes the selected links down at {@code at} for {@code duration} seconds (infinity = permanent). */
	void linkDown(LinkSelect target, double at, double duration);

	/** Degrades the selected links at {@code at} for {@code duration} seconds. */
	void degrade(LinkSelect target, double at, double duration, Degradation degradation);

	/** Crashes the selected nodes at {@code at}; they restart according to {@code recovery}. */
	void crash(Select target, double at, Recovery recovery);

	/**
	 * Splits the network at {@code at} for {@code duration} seconds: every link
	 * between two different groups goes down. Nodes in no group form one more
	 * group. A node listed in several groups belongs to the first.
	 */
	void partition(List<Select> groups, double at, double duration);

	/** Runs custom code at an absolute time, e.g. to draw the next fault. */
	void at(double time, Runnable action);

	/** @return the current simulation time */
	double now();

	/** @return the run length, seconds */
	double duration();
}
