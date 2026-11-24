package zip.hedlund.pureedgesim.edgesdk.algorithm;

import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;

/**
 * A belief an algorithm declares about another node, for the detection metrics
 * only: node {@code by} now suspects (or trusts again) node {@code target}.
 *
 * @param by        the node holding the belief
 * @param target    the node the belief is about
 * @param suspected true for a suspicion, false when trust is restored
 */
public record Declaration(NodeRef by, NodeRef target, boolean suspected) {

	public static Declaration suspect(NodeRef by, NodeRef target) {
		return new Declaration(by, target, true);
	}

	public static Declaration trust(NodeRef by, NodeRef target) {
		return new Declaration(by, target, false);
	}
}
