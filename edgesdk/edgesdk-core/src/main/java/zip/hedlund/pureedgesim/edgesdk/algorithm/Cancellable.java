package zip.hedlund.pureedgesim.edgesdk.algorithm;

/**
 * A scheduled action that can be cancelled.
 */
public interface Cancellable {

	void cancel();

	boolean isCancelled();
}
