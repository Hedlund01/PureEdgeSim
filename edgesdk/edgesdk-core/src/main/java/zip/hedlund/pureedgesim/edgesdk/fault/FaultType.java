package zip.hedlund.pureedgesim.edgesdk.fault;

/** The kinds of fault the framework injects. */
public enum FaultType {
	/** A node stops for good. */
	CRASH_STOP,
	/** A node stops and restarts later, with its volatile state lost. */
	CRASH_RECOVERY,
	/** A link stops carrying traffic. */
	LINK_DOWN,
	/** A link gets slower or lossier. */
	LINK_DEGRADED,
	/** The network splits into isolated groups. */
	PARTITION
}
