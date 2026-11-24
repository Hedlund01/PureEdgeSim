package zip.hedlund.pureedgesim.edgesdk.metrics;

/**
 * A belief declared by an algorithm, checked against the ground truth.
 *
 * @param time      when it was declared
 * @param by        declaring node
 * @param target    node it is about
 * @param suspected suspicion (true) or trust (false)
 * @param correct   whether the belief matched the truth at that moment: a
 *                  suspicion is correct when the target was down or unreachable
 *                  from the declarer, a trust when it was reachable
 */
public record DeclarationRecord(double time, String by, String target, boolean suspected, boolean correct) {
}
