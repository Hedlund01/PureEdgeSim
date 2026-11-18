# Scenario `default`: a district of fixed servers and devices

The baseline scenario. Nothing moves: a city district with fixed edge servers in
a latency mesh, a cloud behind a few gateways, and four kinds of fixed device.
The only things that change during a run are the random link and uplink
failures and the two scheduled partitions. It isolates the question "what does
a partition cost a strategy?" from mobility and handovers.

```bash
mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed"          # default is implied
```

## At a glance

| | |
| --- | --- |
| Map | 3 000 m × 3 000 m (9 km²), servers placed uniformly at random |
| Duration | 15 simulated minutes |
| Edge servers | 40 fixed (4–16 cores, 15 000–30 000 MIPS per core, 8–32 GB) |
| Access servers | 60 % → 24 servers that devices attach to |
| Gateways | 15 % → 6 servers with a 200 Mbit/s uplink to the cloud |
| Devices | 90, all fixed |
| Mobility | none (no handovers) |
| Random failures | mesh links and uplinks 0.5 /h each; servers do not fail |
| Strategies | `FLOCKY_LATENCY`, `FLOCKY_SCORED`, `ACCESS_SERVER`, `CLOUD_ONLY`, `RETRY`, `REPLICATED` |

## Devices and services

The device mix is fixed by percentage in `edge_devices.xml`; with 90 devices the
generator makes 36 / 23 / 22 / 9 of them.

| Device | Count | Link to access server | Service | Request → result | Work | Rate | Deadline |
| --- | --- | --- | --- | --- | --- | --- | --- |
| IoT sensor | 36 | cellular, 100 Mbit/s, 30 ms | Sensor telemetry | 4 KB → 1 KB | 200 MI | 12 /min | 1 s |
| Security camera | 23 | ethernet, 1 Gbit/s, 2 ms | Video analytics | 400 KB → 5 KB | 15 000 MI | 6 /min | 2 s |
| Smartphone | 22 | Wi-Fi, 300 Mbit/s, 5 ms | AR assistant | 150 KB → 30 KB | 3 000 MI | 20 /min | 0.5 s |
| Payment terminal | 9 | ethernet, 1 Gbit/s, 2 ms | Payment authorization | 8 KB → 2 KB | 1 500 MI | 2 /min | 3 s |

Arrivals are Poisson per device. Every service expects its result back
(`tasks.delivery=result`) and no service asks for a trait, so any edge server or
the cloud can run any task. About 15 100 tasks are generated per run.

What each service needs (`services.<n>.requirement` in `testbed.properties`):

| Service | Requirement | Window | Target |
| --- | --- | --- | --- |
| Video analytics | `deadline`: on time, losses accepted | | 95 % |
| Sensor telemetry | `lossless`: never lost, late or not | 60 s | 99.9 % |
| AR assistant | `deadline` | | 95 % |
| Payment authorization | `lossless` | 30 s | 99.9 % |

## Distances and how far communication reaches

| Link | Range rule | Latency | Bandwidth |
| --- | --- | --- | --- |
| Device ↔ access server (radio) | ≤ **300 m** (`devices.radio_range_m`). Devices are placed within 150 m (`devices.radius_m`) of their access server, so every device starts in range | by device type: 2 ms ethernet, 5 ms Wi-Fi, 30 ms cellular | 1 000 / 300 / 100 Mbit/s, shared by the transfers on it |
| Server ↔ server (mesh) | ≤ **650 m** (`mesh.link_range_m`), then the nearest servers are added until each has ≥ 2 links, then the shortest bridge between disconnected parts until the mesh is connected | 1 ms + 4 ms/km: **3.6 ms** at 650 m, ~2.7 ms for the mean link of ~420 m | 1 Gbit/s |
| Gateway ↔ cloud (uplink) | one per gateway, distance-free | 30 ms | 200 Mbit/s |

What that gives on this map. The mesh figures are from the five replications'
logs; the rest from 200 random draws of the same geometry, rebuilding the mesh
with the testbed's rules (one-way latencies, before queueing):

| Property | Value |
| --- | --- |
| Mesh links / average degree | 80–111 links, degree **4.0–5.6** |
| Longest mesh link (added by `min_degree` or to connect the mesh) | ~850 m on average, so some links exceed 650 m |
| Hops between two random servers | mean **4.3**, 95th percentile 9 |
| Mesh latency between two random servers | mean **12.8 ms**, 95th percentile 27 ms |
| Access server → nearest gateway | mean 3.5 ms, 95th percentile 10 ms; +30 ms to the cloud |
| Map covered by the 24 access servers' 300 m radios | **≈ 50 %** (σ ≈ 4 %) |
| Devices with a second access server in radio range | **≈ 49 %** |

- Coverage does not matter while nothing moves, but it decides **failover**:
  when a device's access server fails (`devices.failover=true`) the device looks
  for another access server within 300 m. About half the devices have one; the
  other half are cut off until the server is repaired. (Servers do not fail in
  this scenario unless `failures.node_failure_rate_per_hour` is raised.)
- **Reaching the cloud** costs the mesh hops to a gateway plus 30 ms each way.
  That fits in the 1–3 s budgets but takes a real share of the AR assistant's
  0.5 s once the 3 000 MI frame has run.
- The ethernet devices (cameras, terminals) follow the same 300 m attachment
  rule as the radio devices: the testbed treats every device's first hop as a
  link to one access server within range.
- **Time quantisation.** Each hop's arrival is rounded up to the 0.05 s network
  step, so even a 4 KB reading costs ≥ 50 ms per hop in simulated time. Read
  absolute delays with that in mind; comparisons between strategies are not
  affected.

## Partitions

| # | When | Groups | Cloud kept by |
| --- | --- | --- | --- |
| 1 | 300–600 s | `geo:2`: west and east halves, 20 servers each | the west group only |
| 2 | 690–810 s | `random:3`: three random groups of ~13 servers | one random group, or none |

Example from replication 0, partition 1: the west holds 10 access servers, 3
gateways and 38 devices and reaches the cloud from all 10 access servers; the
east holds 14 access servers, 3 gateways (uplinks cut) and 52 devices, with no
cloud. In partition 2 none of the three groups kept the cloud.

## Results (5 replications, mean and 95 % CI)

Every algorithm runs on the same continuum, failures, partitions and tasks; a
task counts once, judged against the requirement of its service (on time for a
`deadline` service, done at all within its window for a `lossless` one).

| Algorithm | Requirement met | … during partitions | Lost | p95 delay | Cost |
| --- | --- | --- | --- | --- | --- |
| `ACCESS_SERVER` | 99.85 % [99.85, 99.85] | 100.00 % | 0.00 % | 732 ms | 78 instances |
| `FLOCKY_LATENCY` | 99.84 % [99.83, 99.85] | 99.97 % | 0.01 % | 756 ms | 75 instances, 28 kbit/s per server of control traffic |
| `FLOCKY_SCORED` | 99.83 % [99.80, 99.86] | 99.95 % | 0.02 % | 756 ms | 80 instances, 28 kbit/s |
| `RETRY` | 99.85 % [99.85, 99.85] | 100.00 % | 0.00 % | 732 ms | 0.2 kbit/s of acknowledgements |
| `REPLICATED` | 99.89 % [99.87, 99.91] | 100.00 % | 0.00 % | 649 ms | 2 runs per task, 47 % of them redundant |
| `CLOUD_ONLY` | 69.1 % [65.9, 72.4] | 33.5 % | 30.9 % | 409 ms | none at the edge |

All four services reach their targets under every edge strategy; `CLOUD_ONLY`
reaches none. Nothing moves and no server fails here, so no task meets a
detached device, a handover or a failed host: what separates the strategies is
where they run the tasks. Every edge strategy keeps each device's tasks in its
own partition group, so the partitions cost almost nothing; only the AR
assistant, whose 0.5 s deadline is missed now and then by a busy server, falls
short of 100 %. `REPLICATED` trims the tail of the delay by running every task
twice, at the cost of half its runs. `CLOUD_ONLY` loses every task of a group
without the cloud: two thirds of them during the partitions.
