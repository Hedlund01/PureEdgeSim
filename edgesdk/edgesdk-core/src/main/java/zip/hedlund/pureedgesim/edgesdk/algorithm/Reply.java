package zip.hedlund.pureedgesim.edgesdk.algorithm;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * The reply to a request.
 *
 * @param from    the node that replied
 * @param payload the reply
 * @param rtt     time from sending the request to receiving the reply, seconds
 */
public record Reply(NodeRef from, Object payload, double rtt) {
}
