package zip.hedlund.pureedgesim.edgesdk.model;

/**
 * What packet loss does to a transfer on one hop.
 */
public enum LossModel {
	/**
	 * Link-layer retransmission (ARQ): lost packets are sent again, which costs one
	 * extra round trip per round. The transfer is lost only when packets are still
	 * missing after {@link ChannelSpec#maxRetries()} rounds.
	 */
	RETRANSMIT,
	/** Datagram semantics: one lost packet loses the whole transfer. */
	DROP
}
