package zip.hedlund.pureedgesim.edgesdk.network;

/** Why a transfer was lost (ground truth; senders are not told). */
public enum LossReason {
	/** No live path when it reached a node on its way. */
	NO_ROUTE,
	/** A link of its path went down while it was on it. */
	LINK_LOST,
	/** Packet loss beyond what retransmissions recover. */
	PACKET_LOSS,
	/** Its source or destination was down. */
	ENDPOINT_DOWN
}
