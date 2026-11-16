package partitiontestbed;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.taskgenerator.Task;

/**
 * An orchestration algorithm under test: the one thing that differs between two
 * runs of the testbed.
 * <p>
 * The testbed builds the continuum, sends the tasks, breaks the network and
 * measures what happens. Whenever a device sends a task and its service does not
 * run anywhere, the testbed asks the strategy to deploy it, makes the task wait
 * the time the strategy says the deployment took, and sends it to the host it
 * chose. The strategy sees the network only through the {@link TestbedWorld}, and
 * must respect what it says: a server it cannot reach on the live topology cannot
 * be told anything.
 * <p>
 * Placing a service is all a strategy must do. It can also decide for every
 * task, with {@link #route(Task, TestbedWorld.Placement)}, which is what a load
 * balancer or a scheduler that works task by task implements, and it is told of
 * every task that fails, when it decides whether the placement survives
 * ({@link #taskFailed(Task, TestbedWorld.Placement)}).
 * <p>
 * A strategy is registered under one or more algorithm names with
 * {@link Testbed#register(String, java.util.function.Supplier)}, and selected by
 * {@code orchestration_algorithms} in {@code simulation_parameters.properties}, so
 * one run can sweep several strategies over the same edge layer, the same
 * failures and the same partitions.
 *
 * @see partitiontestbed.flocky.FlockyStrategy
 */
public interface PlacementStrategy {

	/**
	 * Called once the continuum is built and before the first event: the place to
	 * read its own configuration, create its state for every server, and create
	 * the simulation entities that run its periodic work.
	 *
	 * @param world the testbed of this run
	 */
	void initialize(TestbedWorld world);

	/**
	 * Deploys a service for a device. The testbed records the placement when the
	 * result says it succeeded, and has already ended the previous placement of the
	 * device, if any, through {@link #release(TestbedWorld.Placement)}.
	 *
	 * @param device  the device whose task found its service not running
	 * @param home    its access server
	 * @param service the service it calls
	 * @return what the strategy did, and how long it took
	 */
	Deployment deploy(ComputingNode device, EdgeServer home, Service service);

	/**
	 * Ends a placement: the device's service is moved away, because a task to it
	 * failed or the strategy itself decided it. Whatever the host held for it is
	 * the strategy's to free.
	 *
	 * @param placement the placement that ends
	 */
	void release(TestbedWorld.Placement placement);

	/**
	 * Chooses where one task runs. Called for every task whose service is placed,
	 * just before it leaves the access server of its device, or, in an edge
	 * pipeline, the host of the stage before it.
	 *
	 * @param task      the task, whose {@link Task#getSubtaskName()} names its stage
	 *                  when its application is a graph of subtasks
	 * @param placement where the service of the device runs
	 * @return the node that runs this task, the {@link EdgeServer#node} of a server
	 *         or {@link TestbedWorld#cloud()}, or {@code null} for the host of the
	 *         placement, which is the default
	 */
	default ComputingNode route(Task task, TestbedWorld.Placement placement) {
		return null;
	}

	/**
	 * Called when a task of a placed service fails: lost on the network, refused
	 * by its host, or late ({@link Task#getFailureReason()} says which). A task of
	 * a fire and forget service that is lost fails here too, although its device
	 * never learns of it.
	 *
	 * @param task      the task that failed
	 * @param placement where the service of its device runs
	 * @return whether to end the placement, so that the next task of the device
	 *         deploys the service again; {@code false} keeps it. The default ends
	 *         it, whatever the reason.
	 */
	default boolean taskFailed(Task task, TestbedWorld.Placement placement) {
		return true;
	}

	/**
	 * Called when a task succeeds: its result reached the device, or, when nothing
	 * is to come back, it ran on its host in time.
	 *
	 * @param task      the task
	 * @param placement where the service of its device runs, or {@code null} when
	 *                  it was moved away meanwhile
	 */
	default void taskCompleted(Task task, TestbedWorld.Placement placement) {
	}

	/**
	 * Called when a device attached to another access server, because the one it
	 * had failed or went out of range. Its placement has already ended, through
	 * {@link #release(TestbedWorld.Placement)}.
	 *
	 * @param device the device
	 * @param from   the access server it left
	 * @param to     the access server it attached to
	 */
	default void deviceMoved(ComputingNode device, EdgeServer from, EdgeServer to) {
	}

	/** @return the names of the columns the strategy adds to the timeline file */
	default List<String> timelineColumns() {
		return List.of();
	}

	/**
	 * Called once per row of the timeline.
	 *
	 * @return the values of the columns of {@link #timelineColumns()}, now
	 */
	default List<String> timelineValues() {
		return List.of();
	}

	/** @return the names of the columns {@link Deployment#details()} fills */
	default List<String> deploymentColumns() {
		return List.of();
	}

	/** @return the lines the strategy adds to the report printed at the end */
	default List<String> reportLines() {
		return List.of();
	}

	/**
	 * Writes the strategy's own files, if any.
	 *
	 * @param folder the output folder
	 * @param label  the beginning of the names of the files of this run
	 * @throws IOException when a file cannot be written
	 */
	default void write(Path folder, String label) throws IOException {
	}
}
