package zip.hedlund.pureedgesim.edgesdk.fault;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;

/**
 * Several fault models at once. Each gets its own random stream derived from the
 * run's fault stream, so adding a model does not change the faults of the others.
 */
public final class CompositeFaultModel extends FaultModel {

	private final List<FaultModel> models;

	public CompositeFaultModel(FaultModel... models) {
		this.models = List.of(models);
	}

	public List<FaultModel> models() {
		return models;
	}

	@Override
	public void install(FaultScheduler scheduler, DesignTopology topology, Random rng) {
		long base = rng.nextLong();
		for (int i = 0; i < models.size(); i++)
			models.get(i).install(scheduler, topology, new Random(base ^ (0x9E3779B97F4A7C15L * (i + 1))));
	}

	@Override
	public String label() {
		return models.stream().map(FaultModel::label).collect(Collectors.joining("+"));
	}
}
