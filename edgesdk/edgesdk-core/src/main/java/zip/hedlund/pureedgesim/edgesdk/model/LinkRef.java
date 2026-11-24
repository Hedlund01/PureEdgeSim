package zip.hedlund.pureedgesim.edgesdk.model;

/**
 * Identifies a link by its endpoints and channel.
 */
public record LinkRef(String a, String b, String channel) {

	/** @return true when this link joins the two nodes, in either order */
	public boolean joins(String x, String y) {
		return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
	}

	@Override
	public String toString() {
		return a + "<->" + b + "(" + channel + ")";
	}
}
