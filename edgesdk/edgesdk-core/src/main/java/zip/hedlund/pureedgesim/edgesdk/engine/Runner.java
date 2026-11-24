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
	 * PureEdgeSim's static settings are restored after the run, also when it fails.
	 *
	 * @param quiet silence PureEdgeSim's console output (standard out and error)
	 *              during the run
	 */
	public static RunData run(RunSpec spec, boolean quiet) {
		RunContext ctx = RunContext.open(spec);
		PrintStream out = System.out;
		PrintStream err = System.err;
		Runnable restore = null;
		try {
			restore = ParametersBridge.snapshot();
			ParametersBridge.apply(spec);
			if (quiet) {
				PrintStream none = new PrintStream(OutputStream.nullOutputStream());
				System.setOut(none);
				System.setErr(none);
			}
			new SdkSimulation(spec.scenario().design().nodes(zip.hedlund.pureedgesim.edgesdk.model.Tier.END_DEVICE)
					.size()).launchSimulation();
		} finally {
			System.setOut(out);
			System.setErr(err);
			try {
				if (restore != null)
					restore.run();
			} finally {
				ctx.close();
			}
		}
		if (ctx.failure() != null)
			throw new SdkRunException("run " + spec.runId() + " failed: " + ctx.failure(), ctx.failure());
		if (!ctx.completed())
			throw new SdkRunException("run " + spec.runId() + " did not complete (see the PureEdgeSim log above)",
					null);
		return ctx.result();
	}
}
