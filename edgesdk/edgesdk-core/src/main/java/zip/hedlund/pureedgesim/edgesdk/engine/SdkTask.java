package zip.hedlund.pureedgesim.edgesdk.engine;

import com.mechalikh.pureedgesim.taskgenerator.DefaultTask;

/**
 * A PureEdgeSim task that carries the part of an SDK attempt it executes, and the crash
 * epoch of the node it was submitted to.
 */
public final class SdkTask extends DefaultTask {

	final TaskEngine.Part part;
	int epoch;

	SdkTask(int id, TaskEngine.Part part) {
		super(id);
		this.part = part;
	}
}
