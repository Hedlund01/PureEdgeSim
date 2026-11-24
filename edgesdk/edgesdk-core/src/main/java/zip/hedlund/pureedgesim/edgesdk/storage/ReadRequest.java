package zip.hedlund.pureedgesim.edgesdk.storage;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * A client read.
 *
 * @param id       unique id within the run
 * @param key      the key
 * @param client   the end device issuing it
 * @param issuedAt issue time
 * @param deadline absolute time by which it must be answered
 */
public record ReadRequest(long id, String key, NodeRef client, double issuedAt, double deadline) {
}
