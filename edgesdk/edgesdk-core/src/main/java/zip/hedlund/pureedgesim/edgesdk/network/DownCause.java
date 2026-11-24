package zip.hedlund.pureedgesim.edgesdk.network;

/**
 * Why a link is down. A link is up only when it has no cause left; every fault
 * adds and later removes its own cause, so overlapping faults compose.
 */
public enum DownCause {
	/** A link failure (scripted or stochastic). */
	FAULT,
	/** A network partition separates its endpoints. */
	PARTITION,
	/** Its endpoints moved out of the channel's radio range. */
	OUT_OF_RANGE,
	/** One of its endpoints crashed. */
	ENDPOINT_CRASHED
}
