---
description: "Default values of the Edge SDK presets (devices, edge nodes, clouds, channels, workloads) and the reasoning behind them."
---
# Edge SDK presets

Every preset is an immutable record and every value can be overridden:

```java
NodeSpec bigUav = DevicePresets.UAV.withCompute(c -> c.withMipsPerCore(8_000))
                                   .withEnergy(e -> e.withCapacityWh(150).withPropulsionW(120));
ChannelSpec slowWifi = ChannelPresets.WIFI.withBandwidthMbps(20, 20).withRangeM(60).named("slow-wifi");
```

The values are **order-of-magnitude defaults** for typical current hardware,
chosen so that relative comparisons are meaningful (a truck server is much
faster than a car computer, LoRa is much slower than Wi-Fi). They are not
measurements of a specific product. When your claim depends on a value,
override it with your own measurement and report it. The preset tests
(`PresetsTest`) pin the values below so that a change is deliberate.

## End devices (`DevicePresets`)

| Preset | Cores × MIPS | RAM / storage | Energy | Mobility | Channels | Reasoning |
| --- | --- | --- | --- | --- | --- | --- |
| `UAV` | 4 × 4 000 | 4 GB / 32 GB | battery 77 Wh, idle 5 W, max 15 W | Gauss-Markov 5–15 m/s | Wi-Fi, RF, 5G | Jetson-Nano-class companion computer (4 ARM A57 cores, 5–10 W modes). 77 Wh is a Mavic-3-class battery. Gauss-Markov gives the smooth, correlated paths of flight. Propulsion (~100–200 W for a small quadcopter) is off by default because it would dominate the energy figures; enable it with `withPropulsionW`. |
| `UGV` | 6 × 6 000 | 8 GB / 64 GB | battery 500 Wh, 10 / 30 W | random waypoint 1–3 m/s | Wi-Fi, RF, 4G | Xavier-NX-class robot computer; a ground robot carries a larger pack and moves at walking speed. |
| `IOT_SENSOR` | 1 × 100 | 0.25 MB / 4 MB | battery 7.5 Wh, 0.01 / 0.1 W | static | LoRa | A Cortex-M MCU with two AA cells (2 × 1.5 V × 2.5 Ah). It does not relay. |
| `SMARTPHONE` | 8 × 8 000 | 8 GB / 128 GB | battery 15 Wh, 0.5 / 5 W | random walk 0.5–2 m/s | Wi-Fi, 4G, 5G | A 4 000 mAh × 3.85 V battery; pedestrian speeds. |
| `WEARABLE` | 2 × 1 000 | 1 GB / 8 GB | battery 1.2 Wh, 0.05 / 0.5 W | random walk 0.5–2 m/s | Wi-Fi | Smartwatch class (about 300 mAh). BLE is not modelled. |

End devices do not relay traffic between other nodes (`relays = false`).

## Edge nodes (`EdgePresets`)

| Preset | Tier | Cores × MIPS | RAM / storage | Power | Mobility | Channels | Reasoning |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `ROADSIDE_SERVER` | static | 16 × 25 000 | 64 GB / 1 TB | mains, 100 / 300 W | static | Wi-Fi, 4G, 5G, Ethernet, fibre, LoRa, RF | A 1U edge server at a roadside unit or on premises; it carries the radios and a LoRa gateway. |
| `MICRO_DC` | static | 64 × 25 000 | 256 GB / 10 TB | mains, 500 / 2 000 W | static | Wi-Fi, 4G, 5G, Ethernet, fibre | A small rack (a few servers) near the devices. |
| `TRUCK_SERVER` | mobile | 32 × 25 000 | 128 GB / 4 TB | vehicle, 200 / 800 W | random waypoint 5–25 m/s | Wi-Fi, RF, 4G, 5G | A ruggedised rack in a truck (18–90 km/h), with radios for its devices and cellular backhaul. |
| `CAR_COMPUTE` | mobile | 8 × 15 000 | 16 GB / 256 GB | vehicle, 30 / 150 W | random waypoint 5–30 m/s | Wi-Fi, 4G, 5G | An in-vehicle computer: much less capacity than the truck. |

## Clouds (`CloudPresets`)

| Preset | Cores × MIPS | Power | Channels | Reasoning |
| --- | --- | --- | --- | --- |
| `REGIONAL_DC` | 1 000 × 50 000 | 20 kW idle / 100 kW max | fibre, Ethernet, 4G, 5G | A regional cloud zone as seen by one application: effectively unlimited compute, far away. |
| `HYPERSCALE_DC` | 10 000 × 50 000 | 200 kW / 1 MW | same | Ten times the regional zone. |

The cellular channels are listed so that a moving edge node or a device can
reach the cloud over an operator network (`connect("truck", "cloud", CELLULAR_5G…)`).
Distance to the cloud is modelled by the channel's latency (fibre: 10 ms plus
0.005 ms per km).

## Channels (`ChannelPresets`)

| Preset | Up / down | Latency (one way, per hop) | Range | Packet loss | Energy | Loss model | Reasoning |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `LORA` | 5.5 kbit/s | 50 ms | 5 km | 1 % | 2.4e-5 J/bit | drop | SF7/125 kHz gives ≈ 5.47 kbit/s. Range is a few km in open areas. About 40 mA at 3.3 V for 5.5 kbit/s ≈ 24 µJ/bit. LoRaWAN class A is unacknowledged, hence `DROP`. 51-byte packets. |
| `RF` | 250 kbit/s | 10 ms | 1 km | 2 % | 1e-6 J/bit | retransmit | A generic sub-GHz telemetry or mesh radio (e.g. 900 MHz). |
| `WIFI` | 100 / 100 Mbit/s | 2 ms | 100 m | 0.5 % | 1e-8 J/bit | retransmit | The effective throughput of a shared 802.11ac/ax cell, not the PHY peak. Measured Wi-Fi energy is in the 5–20 nJ/bit range. MAC ARQ, so retransmit. |
| `CELLULAR_4G` | 10 / 40 Mbit/s | 30 ms | 2 km | 1 % | 1e-7 J/bit | retransmit | Typical per-user LTE rates and the radio part of the latency. LTE is the most energy-hungry radio per bit here. HARQ/RLC retransmission. |
| `CELLULAR_5G` | 50 / 300 Mbit/s | 10 ms | 500 m | 0.1 % | 3e-8 J/bit | retransmit | 5G NR mid-band per-user rates, smaller cells. |
| `ETHERNET` | 1 Gbit/s | 0.5 ms | wired | 0.001 % | 5e-9 J/bit | retransmit | Gigabit LAN. |
| `FIBER` | 10 Gbit/s | 10 ms + 0.005 ms/km | wired | 0.01 % | 1e-9 J/bit | retransmit | Metro and backhaul: access and routing delay plus light in fibre (~5 µs/km). |

How the channel parameters are used:

- **Bandwidth** is shared equally between the transfers on a link, per
  direction. `a → b` uses `up`, `b → a` uses `down`.
- **Latency** is added per hop, with the per-km part computed from the current
  distance between the endpoints.
- **Range.** A wireless link goes down (emergent fault, cause `OUT_OF_RANGE`)
  when its endpoints are farther apart than the range.
- **Packet loss** is drawn per packet (`packetBytes`) on every hop.
  `RETRANSMIT` adds one round trip per retransmission round and gives up after
  `maxRetries` (3) rounds. `DROP` loses the transfer if any packet is lost.
- **Energy per bit** is charged on every hop, half to each endpoint.

## Workloads (`WorkloadPresets`)

| Preset | Rate per device | Length | In / out | Deadline | Container |
| --- | --- | --- | --- | --- | --- |
| `SENSOR_TELEMETRY` | 6 / min | 50 MI | 2 KB / 200 B | 10 s | 8 MB |
| `VIDEO_ANALYTICS` | 12 / min | 4 000 MI | 500 KB / 2 KB | 2 s | 256 MB |
| `CONTROL_LOOP` | 60 / min | 20 MI | 500 B / 100 B | 0.2 s | 4 MB |

Arrivals are Poisson, drawn before the run from the workload seed.

## The information boundary, and writing a failure detector

Algorithms are never told about faults (see
[architecture-analysis.md](architecture-analysis.md#5-the-information-boundary)).
To react to failures, an algorithm detects them from its own messages:

```java
public class MyPolicy extends OrchestrationPolicy {
    private HeartbeatDetector detector;

    @Override public void onStart(SdkContext ctx) {
        detector = new HeartbeatDetector(ctx, 1.0 /* period */, 0.5 /* timeout */, 2 /* misses */);
        for (NodeRef device : ctx.topology().nodes(Tier.END_DEVICE))
            detector.monitor(device, ctx.topology().edgeNodes());
    }

    @Override public void onNodeRestarted(NodeRef at) {   // a crash wiped the detector state of `at`
        if (ctx.topology().node(at).tier() == Tier.END_DEVICE)
            detector.monitor(at, ctx.topology().edgeNodes());
    }

    @Override public Placement place(NodeRef device, TaskRequest task) {
        return ctx.topology().byDesignLatency(device, ctx.topology().edgeNodes()).stream()
                  .filter(s -> !detector.suspects(device, s)).findFirst()
                  .map(Placement::on).orElse(Placement.local());
    }
}
```

The probes are real messages, counted in `control_kbps_per_node`. Every
running node answers them by default (`Algorithm.onRequest`), so call
`super.onRequest(...)` for requests you do not handle. Detectors declare their
suspicions with `ctx.declare(...)`, so the SDK can report detection latency,
false suspicions and missed faults. To write your own detector, send requests
with `ctx.request`, count timeouts, and declare changes of belief.
