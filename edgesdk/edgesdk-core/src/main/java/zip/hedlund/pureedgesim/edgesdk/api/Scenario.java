package zip.hedlund.pureedgesim.edgesdk.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import zip.hedlund.pureedgesim.edgesdk.fault.FaultModel;
import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.WorkloadSpec;
import zip.hedlund.pureedgesim.edgesdk.storage.StorageWorkload;

/**
 * A complete, validated system description: nodes, links, groups, workloads and
 * a fault model. It holds no algorithm; algorithms are supplied per run, which
 * is what makes comparing them natural.
 *
 * <pre>{@code
 * Scenario s = Scenario.builder("uav-swarm")
 *     .duration(600).seed(42)
 *     .addEdgeNode("truck", EdgePresets.TRUCK_SERVER)
 *     .addEndDevices(DevicePresets.UAV, 20, Layout.near("truck", 80))
 *     .addCloud("cloud", CloudPresets.REGIONAL_DC)
 *     .connect(Select.tier(Tier.END_DEVICE), Select.name("truck"), ChannelPresets.WIFI)
 *     .connect("truck", "cloud", ChannelPresets.CELLULAR_5G)
 *     .withWorkload(WorkloadPresets.VIDEO_ANALYTICS)
 *     .withFaults(FaultScript.builder().partition(at(120), lasting(60), "uav-*,truck", "cloud").build())
 *     .build();
 * RunResult r = s.run(Candidate.of("local", LocalFirst::new), Path.of("output/uav"));
 * }</pre>
 */
public final class Scenario {

	/** A workload and the devices that generate it. */
	public record WorkloadAssignment(WorkloadSpec workload, Select devices) {
	}

	private final String name;
	private final double duration;
	private final long seed;
	private final double mapWidth;
	private final double mapLength;
	private final DesignTopology design;
	private final List<WorkloadAssignment> workloads;
	private final StorageWorkload storageWorkload;
	private final FaultModel faultModel;
	private final boolean emergentFaults;
	private final boolean volatileStorage;
	private final double sampleInterval;
	private final double networkUpdateInterval;
	private final ScenarioBuilder recipe;

	Scenario(ScenarioBuilder b, DesignTopology design) {
		this.name = b.name;
		this.duration = b.duration;
		this.seed = b.seed;
		this.mapWidth = b.mapWidth;
		this.mapLength = b.mapLength;
		this.design = design;
		this.workloads = List.copyOf(b.workloads);
		this.storageWorkload = b.storageWorkload;
		this.faultModel = b.faultModel;
		this.emergentFaults = b.emergentFaults;
		this.volatileStorage = b.volatileStorage;
		this.sampleInterval = b.sampleInterval;
		this.networkUpdateInterval = b.networkUpdateInterval;
		this.recipe = b.copy();
	}

	public static ScenarioBuilder builder(String name) {
		return new ScenarioBuilder(name);
	}

	/** @return a builder holding this scenario's declarations, to derive variants */
	public ScenarioBuilder toBuilder() {
		return recipe.copy();
	}

	/** @return a copy with another fault model */
	public Scenario withFaults(FaultModel model) {
		return toBuilder().withFaults(model).build();
	}

	/**
	 * Runs one candidate once and writes the result files to {@code output}.
	 *
	 * @return the run's data and metrics
	 */
	public RunResult run(Candidate candidate, Path output) {
		return Experiment.on(this).candidate(candidate).run(output).runs().get(0);
	}

	public String name() {
		return name;
	}

	public double duration() {
		return duration;
	}

	public long seed() {
		return seed;
	}

	public double mapWidth() {
		return mapWidth;
	}

	public double mapLength() {
		return mapLength;
	}

	public DesignTopology design() {
		return design;
	}

	public List<WorkloadAssignment> workloads() {
		return workloads;
	}

	public Optional<StorageWorkload> storageWorkload() {
		return Optional.ofNullable(storageWorkload);
	}

	public FaultModel faultModel() {
		return faultModel;
	}

	/** @return true when links go down as nodes move out of range */
	public boolean emergentFaults() {
		return emergentFaults;
	}

	/** @return true when a crash wipes the node's key-value store */
	public boolean volatileStorage() {
		return volatileStorage;
	}

	public double sampleInterval() {
		return sampleInterval;
	}

	public double networkUpdateInterval() {
		return networkUpdateInterval;
	}
}
