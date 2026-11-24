package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import zip.hedlund.pureedgesim.edgesdk.api.Layout;
import zip.hedlund.pureedgesim.edgesdk.api.Scenario;
import zip.hedlund.pureedgesim.edgesdk.api.Select;
import zip.hedlund.pureedgesim.edgesdk.fault.FaultScript;
import zip.hedlund.pureedgesim.edgesdk.model.DesignTopology;
import zip.hedlund.pureedgesim.edgesdk.model.NodeRef;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.CloudPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.EdgePresets;

class ScenarioBuilderTest {

	@Test
	void buildsNodesLinksAndGroups() {
		Scenario s = TestScenarios.twoRegions(3).build();
		DesignTopology t = s.design();
		assertEquals(9, t.nodes().size());
		assertEquals(6, t.nodes(Tier.END_DEVICE).size());
		assertEquals(6 + 3, t.links().size());
		assertEquals(Set.of("edge-a", "pa-0", "pa-1", "pa-2"), t.group("a").members());
		assertEquals(Set.of("a"), t.groupsOf(NodeRef.of("pa-1")));
	}

	@Test
	void presetsNameDevicesSequentially() {
		Scenario s = Scenario.builder("x").addEdgeNode("e", EdgePresets.ROADSIDE_SERVER)
				.addEndDevices(DevicePresets.UAV, 3).addEndDevices(DevicePresets.UAV, 2)
				.connect(Select.tier(Tier.END_DEVICE), Select.name("e"), ChannelPresets.WIFI).build();
		assertEquals(5, s.design().nodes(Tier.END_DEVICE).size());
		assertTrue(s.design().contains("uav-4"));
	}

	@Test
	void nearLayoutStaysWithinRadiusAndIsDeterministic() {
		Scenario s1 = TestScenarios.twoRegions(5).build();
		Scenario s2 = TestScenarios.twoRegions(5).build();
		var edge = s1.design().node("edge-a").position();
		for (int i = 0; i < 5; i++) {
			var p = s1.design().node("pa-" + i).position();
			assertTrue(p.distanceTo(edge) <= 50.0001);
			assertEquals(p, s2.design().node("pa-" + i).position());
		}
	}

	@Test
	void connectEachLinksToNearest() {
		Scenario s = Scenario.builder("x").addEdgeNode("west", EdgePresets.ROADSIDE_SERVER, Layout.at(0, 0))
				.addEdgeNode("east", EdgePresets.ROADSIDE_SERVER, Layout.at(1000, 0))
				.addEndDevice("d1", DevicePresets.SMARTPHONE, Layout.at(100, 0))
				.addEndDevice("d2", DevicePresets.SMARTPHONE, Layout.at(950, 0))
				.connectEach(Tier.END_DEVICE).toNearest(Tier.EDGE_STATIC).via(ChannelPresets.CELLULAR_4G)
				.connect("west", "east", ChannelPresets.ETHERNET).build();
		assertEquals(Set.of(NodeRef.of("west")), s.design().neighbours(NodeRef.of("d1")));
		assertEquals(Set.of(NodeRef.of("east")), s.design().neighbours(NodeRef.of("d2")));
	}

	@Test
	void designLatencyRespectsRelaying() {
		Scenario s = TestScenarios.twoRegions(1).build();
		// phones do not relay: the path between two phones goes through the edges
		var path = s.design().designPath(NodeRef.of("pa-0"), NodeRef.of("pb-0"));
		assertEquals(NodeRef.of("edge-a"), path.get(1));
		assertTrue(s.design().designLatency(NodeRef.of("pa-0"), NodeRef.of("cloud")) > 0.01);
	}

	@Test
	void unknownNodeFails() {
		var e = assertThrows(IllegalArgumentException.class, () -> Scenario.builder("x")
				.addEdgeNode("e", EdgePresets.ROADSIDE_SERVER).connect("e", "nope", ChannelPresets.WIFI).build());
		assertTrue(e.getMessage().contains("nope"), e.getMessage());
	}

	@Test
	void unsupportedChannelFails() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> Scenario.builder("x").addEdgeNode("e", EdgePresets.ROADSIDE_SERVER)
						.addEndDevice("s", DevicePresets.IOT_SENSOR, Layout.random())
						.connect("s", "e", ChannelPresets.WIFI).build());
		assertTrue(e.getMessage().contains("no wifi interface"), e.getMessage());
	}

	@Test
	void isolatedNodeFails() {
		var e = assertThrows(IllegalArgumentException.class, () -> Scenario.builder("x")
				.addEdgeNode("e", EdgePresets.ROADSIDE_SERVER).addCloud("c", CloudPresets.REGIONAL_DC).build());
		assertTrue(e.getMessage().contains("has no link"), e.getMessage());
		Scenario.builder("x").addEdgeNode("e", EdgePresets.ROADSIDE_SERVER).allowIsolatedNodes().build();
	}

	@Test
	void duplicatesFail() {
		assertThrows(IllegalArgumentException.class,
				() -> Scenario.builder("x").addEdgeNode("e", EdgePresets.ROADSIDE_SERVER)
						.addEdgeNode("e", EdgePresets.MICRO_DC).allowIsolatedNodes().build());
		assertThrows(IllegalArgumentException.class, () -> TestScenarios.twoRegions(1).group("a", Select.all()));
	}

	@Test
	void wrongTierForPresetFails() {
		assertThrows(IllegalArgumentException.class,
				() -> Scenario.builder("x").addEndDevices(EdgePresets.ROADSIDE_SERVER, 2));
	}

	@Test
	void faultsThatTargetNothingFail() {
		var e = assertThrows(IllegalArgumentException.class, () -> TestScenarios.twoRegions(1)
				.withFaults(FaultScript.builder().crash("ghost", 10).build()).build());
		assertTrue(e.getMessage().contains("targets no node"), e.getMessage());
		assertThrows(IllegalArgumentException.class, () -> TestScenarios.twoRegions(1)
				.withFaults(FaultScript.builder().linkDown("pa-0", "cloud", 10, 5).build()).build());
	}

	@Test
	void toBuilderDerivesVariants() {
		Scenario base = TestScenarios.twoRegions(1).build();
		Scenario bigger = base.toBuilder().addEndDevice("extra", DevicePresets.SMARTPHONE, Layout.near("edge-a", 10))
				.connect("extra", "edge-a", ChannelPresets.WIFI).build();
		assertEquals(base.design().nodes().size() + 1, bigger.design().nodes().size());
	}
}
