# Scenario `convoy`: vehicle-mounted edge servers with scout and mapping UAVs

A convoy in the field. Sixteen of the twenty edge servers are built into
vehicles and move; four are fixed base stations, two of which hold the only
uplinks to the cloud. Every server is an access server. Scout UAVs run a
three-stage reconnaissance pipeline on the edge and get route advice back;
mapping UAVs upload video that is stitched and indexed on the edge and never
comes back; ground sensors send telemetry and expect nothing back.

The mesh between vehicles exists by range only, so the convoy splits and merges
on its own, before any scheduled partition. It is the hardest scenario and the
one with the most variance between replications.

```bash
mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed" -Dtestbed.scenario=convoy
```

## At a glance

| | |
| --- | --- |
| Map | 3 000 m × 3 000 m (9 km²), servers' start positions uniform at random |
| Duration | 10 simulated minutes |
| Edge servers | 20 (4–16 cores, 15 000–30 000 MIPS per core, 8–32 GB) |
| Fixed base stations | `dc1`–`dc4` (their positions are random too; they are not at a chosen base) |
| Vehicles | `dc5`–`dc20`, **16 moving servers**, Gauss-Markov at 14 m/s |
| Access servers | all 20 |
| Gateways | `dc1`, `dc2` only, each with a 200 Mbit/s uplink |
| Devices | 50: 18 scout UAVs, 12 mapping UAVs, 20 ground sensors |
| Random failures | mesh links and uplinks 0.5 /h each, **servers 1 /h each** (mean repair 300 s) |
| Strategies | `FLOCKY_LATENCY`, `FLOCKY_SCORED`, `ACCESS_SERVER`, `CLOUD_ONLY`, `RETRY`, `REPLICATED` |

## Devices and services

| Device | Count | Moves | Link | Service | Delivery | Rate | Deadline |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Scout UAV | 18 | 18 m/s, random waypoint, legs 60–240 s, pauses 0–10 s | Wi-Fi, 300 Mbit/s, 5 ms | Reconnaissance pipeline | `result`, edge pipeline | 10 /min | 3 s |
| Mapping UAV | 12 | 10 m/s, Gauss-Markov, no pauses | Wi-Fi, 300 Mbit/s, 5 ms | Terrain mapping | `fire_and_forget`, edge pipeline | 4 /min | 6 s |
| Ground sensor | 20 | no | cellular, 100 Mbit/s, 30 ms | Sensor telemetry (4 KB, 200 MI) | `fire_and_forget` | 12 /min | 1 s |

```
Scout:   UAV ──300 KB──▶ detect (10 000 MI) ──15 KB──▶ fuse (2 500 MI) ──5 KB──▶ advise (800 MI) ──2 KB──▶ UAV
Mapping: UAV ──900 KB──▶ stitch (18 000 MI) ──120 KB──▶ index (4 000 MI)          (nothing returns)
```

Each stage has its own budget (2 s for the scout stages, 4 s for the mapping
stages) and the request as a whole its deadline. A stage whose input was lost
is never released, and is judged lost with it. About 8 700 tasks (stages) are
generated per run.

What each service needs (`services.<n>.requirement` in `testbed.properties`):

| Service | Requirement | Window | Target |
| --- | --- | --- | --- |
| Reconnaissance pipeline | `deadline`: on time, losses accepted | | 90 % |
| Terrain mapping | `lossless`: never lost, late or not | 120 s | 99 % |
| Sensor telemetry | `lossless` | 60 s | 99 % |

## Distances and how far communication reaches

| Link | Range rule | Latency | Bandwidth |
| --- | --- | --- | --- |
| Device ↔ access server (radio) | ≤ **700 m** (`devices.radio_range_m`); devices start within 150 m of their server | 5 ms Wi-Fi, 30 ms cellular | 300 / 100 Mbit/s, shared |
| Base station ↔ base station | ≤ **1 000 m**, plus `min_degree=2` and links that connect the four | 1 ms + 4 ms/km: **5 ms** at 1 km | 1 Gbit/s |
| Any link with a vehicle | exists only while the two are ≤ **1 000 m** apart and in the same partition group (a vehicle can change group, see Partitions); latency follows the distance, every second | 1–5 ms | 1 Gbit/s |
| `dc1`/`dc2` ↔ cloud | the only two uplinks | 30 ms | 200 Mbit/s |

What that gives (mesh figures from the replications' logs at t = 0, the rest
from 200 random draws of the initial geometry):

| Property | Value |
| --- | --- |
| Mesh links / average degree at t = 0 | 45–58 links, degree **4.5–5.8** |
| Hops between two random servers at t = 0 | mean **2.7**, 95th percentile 5 |
| Mesh latency between two random servers at t = 0 | mean **10 ms**, 95th percentile 22 ms |
| Server → nearest of the two gateways at t = 0 | mean 6.8 ms, 95th percentile 16 ms; +30 ms to the cloud |
| Map covered by the 20 servers' 700 m radios at t = 0 | **≈ 93 %** (σ ≈ 4 %) |
| Fixed sensors with a second server in range at t = 0 | **≈ 93 %** |

These t = 0 figures are optimistic. The minimum degree and the guarantee of a
connected mesh hold only between the four base stations: a vehicle has links
only to servers within 1 km, so once the vehicles spread out the mesh breaks
into islands with no partition scheduled at all, and an island without `dc1` or
`dc2` has no cloud. Coverage also changes as vehicles bunch up or spread out.

How long a link lasts: the mean chord of a 700 m disc is ≈ 1 100 m. A scout UAV
at 18 m/s crosses a fixed station's cell in **≈ 60 s**, a mapping UAV in ≈ 110 s.
Against a vehicle moving at 14 m/s the relative speed is up to 32 m/s, so a
scout can leave a vehicle's cell in **≈ 35 s**. A ground sensor attached to a
vehicle loses it as soon as the vehicle drives 700 m away, ≈ 50 s at full speed.
Each departure is a handover that loses what was on the old radio link.

## Partitions

| # | When | Groups | Cloud kept by |
| --- | --- | --- | --- |
| 1 | 180–330 s | `geo:2`: west and east halves, 10 servers each | **none**: the whole edge is cut off from the cloud |
| 2 | 420–520 s | `random:3` (~7 servers each) | one random group, or none |

The groups are drawn when a partition starts, but they are not fixed. A vehicle
that drives out of mesh range of every server of its group, and into range of a
server of another group, asks to join that group; Flocky admits it
(`flocky.discovery.admit_joining`), and from then on its links to the new group
are up and those to its old group down. A UAV or sensor whose nearest access
server in range belongs to another group attaches to it the same way, and
belongs to that group from then on. With `-Dtestbed.partitions.group_transfer=false`
every node keeps the group it was given. Because `dc1` and `dc2` are the only
gateways, and neither moves, a group must hold one of them to have a cloud at
all; joining a group does not bring a cloud with it here.

## Results (5 replications, mean and 95 % CI)

Every algorithm runs on the same continuum, failures, partitions and tasks; a
task counts once, judged against the requirement of its service (on time for a
`deadline` service, done at all within its window for a `lossless` one).

| Algorithm | Requirement met | … during partitions | Lost | p95 delay | Cost |
| --- | --- | --- | --- | --- | --- |
| `RETRY` | 85.1 % [75.1, 95.1] | 78.4 % | 14.8 %, and 0.8 % late | 791 ms | 0.3 kbit/s of acknowledgements |
| `ACCESS_SERVER` | 83.8 % [73.5, 94.0] | 76.9 % | 16.3 % | 744 ms | 58 instances |
| `REPLICATED` | 83.8 % [73.5, 94.0] | 76.9 % | 16.3 % | 686 ms | 1.6 runs per task, 34 % redundant |
| `FLOCKY_SCORED` | 83.1 % [73.0, 93.2] | 75.8 % | 16.9 % | 753 ms | 14 kbit/s per server of control traffic |
| `FLOCKY_LATENCY` | 82.7 % [73.0, 92.4] | 74.9 % | 17.3 % | 753 ms | 14 kbit/s |
| `CLOUD_ONLY` | 40.8 % [34.8, 46.7] | 1.3 % | 59.2 % | 445 ms | none at the edge |

The intervals are wide because the convoy itself differs a lot from one
replication to the next; paired by replication, which removes that, the
differences are clear: `RETRY` meets **+1.3 ± 0.6** points more than
`ACCESS_SERVER` in every replication, and `FLOCKY_LATENCY` **−1.1 ± 1.0**
fewer, its deployments over a mesh that keeps breaking costing it tasks. By
service, under `RETRY`: route advice 82.1 %, terrain map 98.0 % (93.4 % without
retries), telemetry 86.4 %. Under `RETRY` one service reaches its target in
every replication; under the other strategies almost none does.

**Edge cases** (tasks per run that met the case, and the share that still met
their requirement):

| Case | `ACCESS_SERVER` | `FLOCKY_LATENCY` | `RETRY` | `REPLICATED` |
| --- | --- | --- | --- | --- |
| Device out of range (`device_detached`) | 1382 → 1.8 % | 1383 → 1.8 % | 1335 → **6.8 %** | 1382 → 1.8 % |
| Handover in flight | 73 → 30.0 % | 77 → 29.0 % | 42 → **87.6 %** | 73 → 30.0 % |
| Group change in flight | 18 → 4.5 % | 19 → 4.3 % | 7 → **70.3 %** | 18 → 4.5 % |
| Partition started in flight | 9 → 3.3 % | 9 → 3.3 % | 8 → **31.9 %** | 9 → 1.8 % |

The ground sensors lose the most to devices out of range: a sensor whose server
is a vehicle that drove away is cut off until another comes by, longer than the
minute its telemetry may wait.

**What group transfers are worth.** The same five replications of
`FLOCKY_LATENCY` with `-Dtestbed.partitions.group_transfer=false`: 78.2 % of
the requirements met, 64.3 % during the partitions. Paired by replication,
letting vehicles and devices join the group they reach gains **+4.4 ± 1.5
points** over the run and **+10.7 ± 3.8 points** during the partitions, in every
replication: in the convoy the groups drift apart physically, so a fixed group
soon has members far from each other and close to the other group.

## Edge cases

The run produces, on its own, the situations an orchestrator has to survive. The
testbed marks each task that meets one while it is in flight (between its
creation and its deadline), and reports how many of those still met their
requirement:

| Case | How it happens here | Marked as |
| --- | --- | --- |
| A device sends a task and loses its access server before the result is back | a scout or mapping UAV leaves the cell of its server, or the vehicle that is its server drives away | `handover` when it attaches to another server, `device_detached` when there is none |
| A device creates a task while out of range | vehicles spread out and leave parts of the map uncovered | `device_detached` |
| A device changes partition group with a task in flight | it attaches to a server of another group during a partition, admitted by the strategy | `group_change` |
| The host of a task fails while it runs | servers fail once an hour and are cut off (`failures.node_failure_mode=isolate`): what ran on them finishes, but its result cannot leave until they return | `host_failed` |
| A partition starts with a task in flight | the two scheduled partitions | `partition_started` |

Whether the result still reaches the device is the strategy's affair: the
network routes a result to wherever the device is attached when it is sent, but
a result sent while the device is out of range, or lost on a link that breaks,
is gone unless the strategy sends it again.
