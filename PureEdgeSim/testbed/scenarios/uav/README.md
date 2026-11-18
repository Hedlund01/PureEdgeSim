# Scenario `uav`: UAVs offloading video to a partly mobile edge

UAVs fly over a district and offload their video to the edge servers in radio
range. Some of those servers are built into vehicles and move too. The video is
analysed on the edge, and the detections feed a tracking task that also runs on
the edge: only the track goes back to the UAV. Fixed sensors send telemetry and
expect nothing back, and ground crews on foot use an AR assistant. This is the
scenario where devices hand over between access servers, where pipelines run on
the edge, and where one run mixes `result` and `fire_and_forget` delivery.

```bash
mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed" -Dtestbed.scenario=uav
```

## At a glance

| | |
| --- | --- |
| Map | 3 000 m × 3 000 m (9 km²), servers placed uniformly at random |
| Duration | 12 simulated minutes |
| Edge servers | 30 (4–16 cores, 15 000–30 000 MIPS per core, 8–32 GB) |
| Mobile servers | 30 % → **9 vehicles**, random waypoint at 12 m/s (legs of 60–300 s, pauses of 0–30 s) |
| Access servers | 70 % → 21 servers that devices attach to (vehicles can be access servers) |
| Gateways | 15 % → 5 servers with a 200 Mbit/s uplink to the cloud |
| Devices | 60: 18 UAVs and 12 ground crew that move, 30 fixed sensors |
| Random failures | mesh links and uplinks 0.5 /h each, **servers 1 /h each** (mean repair 300 s) |
| Strategies | `FLOCKY_LATENCY`, `FLOCKY_SCORED`, `ACCESS_SERVER`, `CLOUD_ONLY`, `RETRY`, `REPLICATED` |

## Devices and services

| Device | Count | Moves | Link | Service | Delivery | Rate | Deadline |
| --- | --- | --- | --- | --- | --- | --- | --- |
| UAV | 18 | 15 m/s, random waypoint, legs 60–240 s, pauses 0–20 s | Wi-Fi, 300 Mbit/s, 5 ms | Aerial video pipeline | `result`, edge pipeline | 6 /min | 3 s |
| IoT sensor | 30 | no | cellular, 100 Mbit/s, 30 ms | Sensor telemetry (4 KB, 200 MI) | `fire_and_forget` | 12 /min | 1 s |
| Ground crew | 12 | 1.5 m/s (walking), pauses 10–120 s | Wi-Fi, 300 Mbit/s, 5 ms | AR assistant (150 KB, 3 000 MI) | `result` | 20 /min | 0.5 s |

The **aerial video pipeline** is two stages (`services.0.pipeline=edge`):

```
UAV ──400 KB clip──▶ detect (12 000 MI) ──20 KB detections──▶ track (3 000 MI) ──3 KB track──▶ UAV
                     └─────────────── both on the edge, nothing returns in between ──────────┘
```

Each stage has a 2 s budget and the whole request a 3 s deadline. If `detect`
is lost, `track` is never released, and is judged lost with it. About 9 600 tasks
(stages) are generated per run.

What each service needs (`services.<n>.requirement` in `testbed.properties`):

| Service | Requirement | Window | Target |
| --- | --- | --- | --- |
| Aerial video pipeline | `deadline`: on time, losses accepted | | 90 % |
| Sensor telemetry | `lossless`: never lost, late or not | 60 s | 99 % |
| AR assistant | `deadline` | | 95 % |

## Distances and how far communication reaches

| Link | Range rule | Latency | Bandwidth |
| --- | --- | --- | --- |
| Device ↔ access server (radio) | ≤ **500 m** (`devices.radio_range_m`); devices start within 150 m of their access server | 5 ms Wi-Fi, 30 ms cellular | 300 / 100 Mbit/s, shared |
| Fixed server ↔ fixed server | ≤ **750 m** (`mesh.link_range_m`), plus `min_degree=2` and links that connect the mesh | 1 ms + 4 ms/km: **4 ms** at 750 m | 1 Gbit/s |
| Link with a moving server | exists only while the two are ≤ **750 m** apart and in the same partition group (a vehicle can change group, see Partitions); latency recomputed every second | 1 ms + 4 ms/km of the current distance | 1 Gbit/s |
| Gateway ↔ cloud | one per gateway | 30 ms | 200 Mbit/s |

What that gives on this map (mesh figures from the replications' logs, the rest
from 200 random draws of the initial geometry):

| Property | Value |
| --- | --- |
| Mesh links / average degree at t = 0 | 57–81 links, degree **3.8–5.4** |
| Hops between two random servers | mean **3.6**, 95th percentile 7 |
| Mesh latency between two random servers | mean **12 ms**, 95th percentile 25 ms |
| Access server → nearest gateway | mean 3.8 ms, 95th percentile 10.5 ms; +30 ms to the cloud |
| Map covered by the 21 access servers' 500 m radios | **≈ 79 %** (σ ≈ 5 %) |
| Fixed devices with a second access server in range | **≈ 79 %** |

How long a mover stays with one access server: the mean chord of a 500 m disc is
π·500/2 ≈ 785 m, so a UAV at 15 m/s crosses a cell in **≈ 50 s**, a walking crew
member in ≈ 9 min. A vehicle at 12 m/s and a UAV can close or open at up to
27 m/s, so a UAV attached to a vehicle can leave it in under 40 s. Every
departure is a handover: what was on the old radio link is lost and the service
is deployed again from the new access server. About **21 %** of the map is out
of every access server's range at the start, and a UAV flying there is cut off.

Vehicles that are access servers also take their **fixed sensors** with them out
of range: a sensor attached to a vehicle is handed over (or cut off) when the
vehicle drives away. That, and server failures, are why fixed sensors lose tasks
here although they never move.

## Partitions

| # | When | Groups | Cloud kept by |
| --- | --- | --- | --- |
| 1 | 240–420 s | `geo:2`: west and east halves | the west group only |
| 2 | 500–600 s | `random:3` | one random group, or none |

The groups are drawn when a partition starts, but a node that moves can change
group while it lasts:

- a **vehicle** that is out of mesh range of every server of its group and in
  range of a server of another group asks to join that group. Admitted, its
  links to the new group come up and those to the old one go down; if it is a
  gateway, its cloud uplink follows whether the new group keeps the cloud;
- a **UAV or crew member** whose nearest access server in range belongs to
  another group attaches to it, and belongs to that group from then on.

The strategy decides each time (`admitJoin`); Flocky admits, since its
discovery takes any node that pings it (`flocky.discovery.admit_joining=false`
closes its groups). `-Dtestbed.partitions.group_transfer=false` keeps every node
in the group it was given.

## Results (5 replications, mean and 95 % CI)

Every algorithm runs on the same continuum, failures, partitions and tasks; a
task counts once, judged against the requirement of its service (on time for a
`deadline` service, done at all within its window for a `lossless` one).

| Algorithm | Requirement met | … during partitions | Lost | p95 delay | Cost |
| --- | --- | --- | --- | --- | --- |
| `RETRY` | 91.3 % [89.8, 92.8] | 89.8 % | 8.4 %, and 1.5 % late | 642 ms | 0.2 kbit/s of acknowledgements |
| `REPLICATED` | 89.9 % [88.4, 91.3] | 88.2 % | 10.0 % | 554 ms | 1.8 runs per task, 43 % redundant |
| `ACCESS_SERVER` | 89.8 % [88.4, 91.2] | 88.1 % | 10.0 % | 588 ms | 62 instances |
| `FLOCKY_LATENCY` | 89.7 % [88.3, 91.1] | 88.1 % | 10.1 % | 596 ms | 22 kbit/s per server of control traffic |
| `FLOCKY_SCORED` | 89.6 % [88.4, 90.9] | 87.8 % | 10.2 % | 594 ms | 22 kbit/s |
| `CLOUD_ONLY` | 66.0 % [65.4, 66.5] | 29.6 % | 34.1 % | 335 ms | none at the edge |

Paired by replication, `RETRY` meets **+1.5 ± 1.0** points more requirements
than `ACCESS_SERVER`, in every replication, and Flocky meets as many as
`ACCESS_SERVER` (−0.05 ± 0.06). By service, under `RETRY`: video pipeline
80.4 %, telemetry 95.8 % (93.2 % without retries), AR assistant 94.6 %. No
strategy reaches the 90 % target of the video pipeline or the 99 % of telemetry.

**Edge cases** (tasks per run that met the case, and the share that still met
their requirement):

| Case | `ACCESS_SERVER` | `FLOCKY_LATENCY` | `RETRY` | `REPLICATED` |
| --- | --- | --- | --- | --- |
| Device out of range (`device_detached`) | 952 → 0.9 % | 953 → 0.9 % | 945 → **15.0 %** | 952 → 0.9 % |
| Handover in flight | 52 → 31.9 % | 52 → 32.1 % | 38 → **84.3 %** | 51 → 33.1 % |
| Group change in flight | 10 → 10.9 % | 10 → 10.9 % | 6 → **65.3 %** | 10 → 10.9 % |
| Host crashed in flight | 2 → 0 % | 2 → 0 % | 1 → 77.8 % | 1 → 55.6 % |

A task created out of range is lost unless the device keeps it and sends it when
it finds a server, which only `RETRY` does; it saves the telemetry, whose window
is a minute, but not the video or AR tasks, whose deadline has passed by then. A
result whose device handed over is saved by a host that sends it again until it
is acknowledged. A copy on a neighbour saves a task whose host crashed, but
crashes are rare next to radio losses, so `REPLICATED` mainly buys a shorter
tail of delay.

**What group transfers are worth.** The same five replications of
`FLOCKY_LATENCY` with `-Dtestbed.partitions.group_transfer=false`, where no node
changes group: 87.4 % of the requirements met, 81.7 % during the partitions.
Paired by replication, letting nodes join the group they reach gains
**+2.4 ± 0.9 points** over the run and **+6.3 ± 1.8 points** during the
partitions, in every replication.

## Edge cases

The run produces, on its own, the situations an orchestrator has to survive. The
testbed marks each task that meets one while it is in flight (between its
creation and its deadline), and reports how many of those still met their
requirement:

| Case | How it happens here | Marked as |
| --- | --- | --- |
| A device sends a task and loses its access server before the result is back | a UAV or crew member leaves the cell of its server, or the vehicle that is its server drives away | `handover` when it attaches to another server, `device_detached` when there is none |
| A device creates a task while out of range | about 21 % of the map is outside every access server | `device_detached` |
| A device changes partition group with a task in flight | it attaches to a server of another group during a partition, admitted by the strategy | `group_change` |
| The host of a task fails while it runs | servers fail once an hour, and crash (`failures.node_failure_mode=crash`): what ran on them is lost | `host_failed` |
| A partition starts with a task in flight | the two scheduled partitions | `partition_started` |

Whether the result still reaches the device is the strategy's affair: the
network routes a result to wherever the device is attached when it is sent, but
a result sent while the device is out of range, or lost on a link that breaks,
is gone unless the strategy sends it again.
