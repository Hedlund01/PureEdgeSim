package zip.hedlund.pureedgesim.edgesdk.engine;

import com.mechalikh.pureedgesim.taskgenerator.DefaultTask;

/**
 * A PureEdgeSim task that carries the SDK attempt it executes, and the crash
 * epoch of the node it was submitted to.
 */
public final class SdkTask extends DefaultTask {

	final TaskEngine.Attempt attempt;
	int epoch;

	SdkTask(int id, TaskEngine.Attempt attempt) {
		super(id);
		this.attempt = attempt;
	}
}
