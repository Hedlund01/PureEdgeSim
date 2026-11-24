package zip.hedlund.pureedgesim.edgesdk.engine;

import java.io.OutputStream;
import java.io.PrintStream;

import zip.hedlund.pureedgesim.edgesdk.api.SdkRunException;
import zip.hedlund.pureedgesim.edgesdk.metrics.RunData;

/**
 * Runs one {@link RunSpec} through PureEdgeSim and returns what it recorded.
 */
public final class Runner {

	private Runner() {
	}

	/**
	 * @param quiet silence PureEdgeSim's console output during the run
	 */
	public static RunData run(RunSpec spec, boolean quiet) {
		RunContext ctx = RunContext.open(spec);
		PrintStream out = System.out;
		try {
			ParametersBridge.apply(spec);
			if (quiet)
				System.setOut(new PrintStream(OutputStream.nullOutputStream()));
			new SdkSimulation(spec.scenario().design().nodes(zip.hedlund.pureedgesim.edgesdk.model.Tier.END_DEVICE)
					.size()).launchSimulation();
		} finally {
			System.setOut(out);
			ctx.close();
		}
		if (ctx.failure() != null)
			throw new SdkRunException("run " + spec.runId() + " failed: " + ctx.failure(), ctx.failure());
		if (!ctx.completed())
			throw new SdkRunException("run " + spec.runId() + " did not complete (see the PureEdgeSim log above)",
					null);
		return ctx.result();
	}
}
