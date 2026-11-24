package zip.hedlund.pureedgesim.edgesdk.algorithm;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * A task created by an end device.
 *
 * @param id          unique id within the run
 * @param device      the device that created it and wants the result
 * @param workload    the workload it belongs to
 * @param lengthMi    computation, million instructions
 * @param inputBytes  bytes to send to the executing node
 * @param outputBytes bytes of the result
 * @param createdAt   creation time
 * @param deadline    absolute time by which the result must be back
 */
public record TaskRequest(long id, NodeRef device, String workload, double lengthMi, long inputBytes,
		long outputBytes, double createdAt, double deadline) {
}
