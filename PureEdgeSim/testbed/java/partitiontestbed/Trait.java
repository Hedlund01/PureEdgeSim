package partitiontestbed;

/**
 * A capability an edge server advertises, and that a service can require. The
 * names follow the traits of the Flocky paper, which any strategy may use as
 * placement constraints.
 */
public enum Trait {
	/** The server runs on renewable energy. */
	GREEN_ENERGY,
	/** The server can run unikernels in a hardened runtime. */
	SECURE_RUNTIME,
	/** The server encrypts its network traffic, for example over a VPN. */
	NETWORK_ENCRYPTION,
	/** The server can prove its integrity to a remote verifier. */
	ATTESTATION
}
