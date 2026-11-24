package zip.hedlund.pureedgesim.edgesdk.api;

/** A run could not complete; the cause is the exception thrown inside it. */
public class SdkRunException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public SdkRunException(String message, Throwable cause) {
		super(message, cause);
	}
}
