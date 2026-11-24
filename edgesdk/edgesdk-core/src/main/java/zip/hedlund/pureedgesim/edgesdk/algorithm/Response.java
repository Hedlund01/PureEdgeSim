package zip.hedlund.pureedgesim.edgesdk.algorithm;

/**
 * A reply with an explicit size. Return it from {@link Algorithm#onRequest} when
 * the reply is larger than the default of {@value #DEFAULT_BYTES} bytes.
 *
 * @param payload what the requester receives
 * @param bytes   size on the wire
 */
public record Response(Object payload, long bytes) {

	/** Size of a reply that is not a {@code Response}. */
	public static final long DEFAULT_BYTES = 64;

	public static Response of(Object payload, long bytes) {
		return new Response(payload, bytes);
	}
}
