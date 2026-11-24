package zip.hedlund.pureedgesim.edgesdk.fault;

import java.util.List;
import java.util.Random;

import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;

/**
 * A fixed timeline of faults. Build one with {@link FaultScript#builder()} or
 * parse one with {@link FaultScript#parse(String)}.
 */
public final class ScriptedFaultModel extends FaultModel {

	/** One scripted step. */
	public interface Step {
		void schedule(FaultScheduler scheduler);

		/** @return the step written in the script format */
		String describe();
	}

	private final List<Step> steps;
	private final String label;

	ScriptedFaultModel(List<Step> steps, String label) {
		this.steps = List.copyOf(steps);
		this.label = label;
	}

	/** @return the same script with another label */
	public ScriptedFaultModel labelledScript(String newLabel) {
		return new ScriptedFaultModel(steps, newLabel);
	}

	public List<Step> steps() {
		return steps;
	}

	@Override
	public void install(FaultScheduler scheduler, DesignTopology topology, Random rng) {
		for (Step s : steps)
			s.schedule(scheduler);
	}

	@Override
	public String label() {
		return label;
	}

	/** @return the script in the text format */
	public String toScript() {
		StringBuilder sb = new StringBuilder();
		for (Step s : steps)
			sb.append(s.describe()).append('\n');
		return sb.toString();
	}
}
