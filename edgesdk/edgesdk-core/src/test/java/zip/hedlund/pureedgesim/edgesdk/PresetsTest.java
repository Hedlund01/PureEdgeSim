package zip.hedlund.pureedgesim.edgesdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import zip.hedlund.pureedgesim.edgesdk.model.ChannelSpec;
import zip.hedlund.pureedgesim.edgesdk.model.MobilitySpec;
import zip.hedlund.pureedgesim.edgesdk.model.NodeSpec;
import zip.hedlund.pureedgesim.edgesdk.model.Tier;
import zip.hedlund.pureedgesim.edgesdk.presets.ChannelPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.CloudPresets;
import zip.hedlund.pureedgesim.edgesdk.presets.DevicePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.EdgePresets;
import zip.hedlund.pureedgesim.edgesdk.presets.WorkloadPresets;

class PresetsTest {

	@Test
	void tiersMatchTheCatalogue() {
		DevicePresets.all().forEach(p -> assertEquals(Tier.END_DEVICE, p.tier(), p.name()));
		assertEquals(Tier.EDGE_STATIC, EdgePresets.ROADSIDE_SERVER.tier());
		assertEquals(Tier.EDGE_STATIC, EdgePresets.MICRO_DC.tier());
		assertEquals(Tier.EDGE_MOBILE, EdgePresets.TRUCK_SERVER.tier());
		assertEquals(Tier.EDGE_MOBILE, EdgePresets.CAR_COMPUTE.tier());
		CloudPresets.all().forEach(p -> assertEquals(Tier.CLOUD, p.tier(), p.name()));
	}

	@Test
	void documentedDefaults() {
		assertEquals(4, DevicePresets.UAV.compute().cores());
		assertEquals(4_000, DevicePresets.UAV.compute().mipsPerCore());
		assertEquals(77, DevicePresets.UAV.energy().capacityWh());
		assertTrue(DevicePresets.UAV.mobility().isMobile());
		assertFalse(DevicePresets.IOT_SENSOR.mobility().isMobile());
		assertTrue(DevicePresets.IOT_SENSOR.supports(ChannelPresets.LORA));
		assertFalse(DevicePresets.IOT_SENSOR.supports(ChannelPresets.WIFI));
		assertEquals(32, EdgePresets.TRUCK_SERVER.compute().cores());
		assertTrue(EdgePresets.TRUCK_SERVER.compute().totalMips() > EdgePresets.CAR_COMPUTE.compute().totalMips());
		assertFalse(EdgePresets.ROADSIDE_SERVER.energy().battery());
		assertEquals(10_000, CloudPresets.HYPERSCALE_DC.compute().cores());
		assertEquals(0.0055, ChannelPresets.LORA.upMbps());
		assertEquals(Double.POSITIVE_INFINITY, ChannelPresets.FIBER.rangeM());
		assertTrue(ChannelPresets.CELLULAR_5G.downMbps() > ChannelPresets.CELLULAR_4G.downMbps());
		assertEquals(2, WorkloadPresets.VIDEO_ANALYTICS.deadlineS());
	}

	@Test
	void channelOrderingIsSensible() {
		// LoRa has the longest range and lowest bandwidth of the radios
		for (ChannelSpec c : ChannelPresets.all()) {
			if (c.isWireless())
				assertTrue(ChannelPresets.LORA.rangeM() >= c.rangeM(), c.name());
			assertTrue(ChannelPresets.LORA.upMbps() <= c.upMbps(), c.name());
		}
		assertFalse(ChannelPresets.ETHERNET.isWireless());
	}

	@Test
	void withersCopyAndDoNotMutate() {
		NodeSpec big = DevicePresets.UAV.withCompute(c -> c.withMipsPerCore(8_000))
				.withEnergy(e -> e.withCapacityWh(150)).named("big-uav");
		assertNotSame(DevicePresets.UAV, big);
		assertEquals(8_000, big.compute().mipsPerCore());
		assertEquals(150, big.energy().capacityWh());
		assertEquals("big-uav", big.name());
		assertEquals(4_000, DevicePresets.UAV.compute().mipsPerCore());
		assertEquals(77, DevicePresets.UAV.energy().capacityWh());
		NodeSpec withLora = DevicePresets.UAV.withChannel(ChannelPresets.LORA);
		assertTrue(withLora.supports(ChannelPresets.LORA));
		assertFalse(DevicePresets.UAV.supports(ChannelPresets.LORA));
		ChannelSpec slow = ChannelPresets.WIFI.withBandwidthMbps(10, 20).withRangeM(50).named("slow-wifi");
		assertEquals(10, slow.upMbps());
		assertEquals(100, ChannelPresets.WIFI.upMbps());
	}

	@Test
	void invalidValuesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> ChannelPresets.WIFI.withPacketLoss(1.5));
		assertThrows(IllegalArgumentException.class, () -> DevicePresets.UAV.withCompute(c -> c.withCores(0)));
		assertThrows(IllegalArgumentException.class,
				() -> EdgePresets.ROADSIDE_SERVER.withMobility(MobilitySpec.randomWalk(1, 2)));
		assertThrows(IllegalArgumentException.class, () -> DevicePresets.UAV.withEnergy(e -> e.withPower(10, 5)));
	}
}
