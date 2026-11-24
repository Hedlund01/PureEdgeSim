package zip.hedlund.pureedgesim.edgesdk.presets;

import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.CELLULAR_4G;
import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.CELLULAR_5G;
import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.ETHERNET;
import static zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets.FIBER;

import java.util.List;

import zip.hedlund.pureedgesim.edgesdk.model.ComputeSpec;
import zip.hedlund.pureedgesim.edgesdk.model.EnergySpec;
import zip.hedlund.pureedgesim.edgesdk.model.MemorySpec;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeSpec;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;

/**
 * Default cloud data centers. A scenario can declare several, and every edge node
 * can link to one or more of them. The cellular channels are listed so that
 * mobile edge nodes and devices can reach the cloud over the operator network.
 */
public final class CloudPresets {

	private CloudPresets() {
	}

	/** A regional data center: 1 000 cores at 50 000 MIPS. */
	public static final NodeSpec REGIONAL_DC = new NodeSpec("cloud", Tier.CLOUD, ComputeSpec.of(1_000, 50_000),
			MemorySpec.gb(64_000, 10_000_000), EnergySpec.mains(20_000, 100_000), MobilitySpec.STATIC,
			List.of(FIBER, ETHERNET, CELLULAR_4G, CELLULAR_5G), true);

	/** A hyperscale data center: 10 000 cores at 50 000 MIPS. */
	public static final NodeSpec HYPERSCALE_DC = REGIONAL_DC.named("hyperscale")
			.withCompute(c -> c.withCores(10_000)).withMemory(m -> m.withRamMb(640_000 * 1024.0))
			.withEnergy(e -> e.withPower(200_000, 1_000_000));

	/** @return every preset */
	public static List<NodeSpec> all() {
		return List.of(REGIONAL_DC, HYPERSCALE_DC);
	}
}
