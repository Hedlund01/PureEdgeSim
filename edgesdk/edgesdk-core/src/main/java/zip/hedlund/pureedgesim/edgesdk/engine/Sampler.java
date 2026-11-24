package zip.hedlund.pureedgesim.edgesdk.engine;

import java.util.ArrayList;
import java.util.List;

import zip.hedlund.pureedgesim.edgesdk.metrics.TimelineSample;
import zip.hedlund.pureedgesim.edgesdk.network.Transfer;

/** Samples the state of the run at a fixed period for {@code timeline.csv}. */
final class Sampler {

	private final SdkWorld world;
	private final List<TimelineSample> samples = new ArrayList<>();
	private long lastControlBytes;
	private double lastTime = -1;

	Sampler(SdkWorld world) {
		this.world = world;
	}

	void start(double period) {
		world.after(period, () -> tick(period));
	}

	private void tick(double period) {
		sample();
		if (world.now() + period <= world.duration())
			world.after(period, () -> tick(period));
	}

	void sample() {
		double now = world.now();
		if (now == lastTime)
			return;
		lastTime = now;
		int[] window = world.tasks().drainWindow();
		long control = world.network().bytesSent(Transfer.Kind.CONTROL)
				+ world.network().bytesSent(Transfer.Kind.STORAGE);
		int finished = window[0] + window[1];
		samples.add(new TimelineSample(now, world.live().linksDown(),
				(int) world.nodes().stream().filter(n -> !n.isUp()).count(), world.faultLog().partitionActive(now),
				window[0], window[1], finished == 0 ? Double.NaN : 100.0 * window[0] / finished,
				world.storage() == null ? 0 : world.storage().divergentKeys(), control - lastControlBytes));
		lastControlBytes = control;
	}

	List<TimelineSample> samples() {
		return samples;
	}
}
