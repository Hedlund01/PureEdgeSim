package zip.hedlund.pureedgesim.edgesdk;

import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.api.ScenarioBuilder;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.CloudPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.EdgePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.WorkloadPresets;

/** Small scenarios shared by the tests. */
public final class TestScenarios {

	private TestScenarios() {
	}

	/**
	 * Two static edge servers in two regions, each with its own static phones over
	 * Wi-Fi, both linked to one cloud over fibre and to each other over Ethernet.
	 * Region "a" = edge-a and its phones; region "b" likewise.
	 */
	public static ScenarioBuilder twoRegions(int phonesPerRegion) {
		var phone = DevicePresets.SMARTPHONE.withMobility(MobilitySpec.STATIC);
		ScenarioBuilder b = Scenario.builder("two-regions").duration(120).seed(7).map(1_000, 1_000)
				.addEdgeNode("edge-a", EdgePresets.ROADSIDE_SERVER, Layout.at(200, 500))
				.addEdgeNode("edge-b", EdgePresets.ROADSIDE_SERVER, Layout.at(800, 500))
				.addCloud("cloud", CloudPresets.REGIONAL_DC, Layout.at(500, 900));
		for (int i = 0; i < phonesPerRegion; i++) {
			b.addEndDevice("pa-" + i, phone, Layout.near("edge-a", 50));
			b.addEndDevice("pb-" + i, phone, Layout.near("edge-b", 50));
		}
		return b.connect(Select.name("pa-*"), Select.name("edge-a"), ChannelPresets.WIFI)
				.connect(Select.name("pb-*"), Select.name("edge-b"), ChannelPresets.WIFI)
				.connect("edge-a", "cloud", ChannelPresets.FIBER).connect("edge-b", "cloud", ChannelPresets.FIBER)
				.connect("edge-a", "edge-b", ChannelPresets.ETHERNET)
				.group("a", Select.names("edge-a", "pa-*")).group("b", Select.names("edge-b", "pb-*"))
				.withWorkload(WorkloadPresets.SENSOR_TELEMETRY.withRatePerMinute(30), Select.tier(Tier.END_DEVICE));
	}
}
