package zip.hedlund.pureedgesim.edgesdk.presets;

import java.util.List;

import zip.hedlund.pureedgesim.edgesdk.model.WorkloadSpec;

/**
 * Default task workloads.
 */
public final class WorkloadPresets {

	private WorkloadPresets() {
	}

	/** Small periodic readings, latency tolerant: 2 KB in, 200 B out, 10 s deadline. */
	public static final WorkloadSpec SENSOR_TELEMETRY = new WorkloadSpec("telemetry", 6, 50, 2_000, 200, 10, 8);

	/**
	 * A video frame batch for object detection: 500 KB in, 2 KB out, 4 000 MI,
	 * 2 s deadline.
	 */
	public static final WorkloadSpec VIDEO_ANALYTICS = new WorkloadSpec("video", 12, 4_000, 500_000, 2_000, 2, 256);

	/** A control loop step: tiny, very tight 200 ms deadline. */
	public static final WorkloadSpec CONTROL_LOOP = new WorkloadSpec("control", 60, 20, 500, 100, 0.2, 4);

	/** @return every preset */
	public static List<WorkloadSpec> all() {
		return List.of(SENSOR_TELEMETRY, VIDEO_ANALYTICS, CONTROL_LOOP);
	}
}
