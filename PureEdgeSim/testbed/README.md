# Partition testbed: orchestration on an edge-to-cloud continuum that splits

## What it is

A testbed, built on PureEdgeSim, for asking one question of an orchestration
algorithm: does it keep serving the devices when the network under it splits
into partitions, while the devices keep sending tasks?

The testbed builds an edge-to-cloud continuum of end devices, edge servers and a
cloud data center, sends the devices' stateless tasks through it, and breaks the
network with random failures and scheduled partitions. Every task is counted:
where it ran, how long it took, and why it failed. What decides where the tasks
run is the **strategy** under test, a class behind one interface. The first
strategy is [Flocky](https://doi.org/10.1109/TSC.2025.3631387) (Goethals et al.,
IEEE Transactions on Services Computing, 2026), a decentralised intent-based
orchestrator; others plug in next to it and face exactly the same continuum,
failures and partitions.

How the simulation itself works (the event loop, the life of a task, the network,
compute and mobility models, and what the results can be trusted for) is in
[HOW_IT_WORKS.md](HOW_IT_WORKS.md).

## Layout

```
PureEdgeSim/testbed/
  java/partitiontestbed/          the testbed: continuum, partitions, failures,
                                  routing, tasks, metrics, deployment map
  java/partitiontestbed/flocky/   Flocky, one PlacementStrategy
  scenarios/
    common.properties             what every scenario and every run share
    <scenario>/                   what one scenario sets: testbed.properties,
                                  simulation_parameters.properties and the XMLs
    default/                      a district of fixed servers and devices
    uav/                          UAVs, an edge pipeline, devices and edge
                                  servers that move
    convoy/                       a convoy of vehicle-mounted edge servers with
                                  scout and mapping UAVs
  output/                         CSV files and charts of the runs
PureEdgeSim/test/java/partitiontestbed/   tests of the testbed and of Flocky
```

The testbed is a source folder of its own, outside the examples, compiled with
the library (see the `build-helper` sources in `pom.xml`) and kept out of the
library's javadoc.

## The continuum

| Tier | What | Defined in |
| --- | --- | --- |
| End devices | 90 devices of four types, each calling its own service (below) | `edge_devices.xml` |
| Edge servers | 40 heterogeneous servers (4 to 16 cores, 8 to 32 GB) in a latency mesh; 60% are access servers devices attach to, 15% gateways with a cloud uplink | `edge_datacenters.xml`, `testbed.properties` |
| Cloud data center | one region, reached only through the gateways' uplinks | `cloud.xml` |

| Device type | Share | Radio, power | Calls | Budget |
| --- | --- | --- | --- | --- |
| IoT sensor | 40% | cellular, battery, no processor | Sensor telemetry: 4 KB, 12/min | 1 s |
| Security camera | 25% | ethernet, mains | Video analytics: 400 KB clips, 15000 MI, 6/min | 2 s |
| Smartphone | 25% | wifi, battery | AR assistant: 150 KB frames, 3000 MI, 20/min | 0.5 s |
| Payment terminal | 10% | ethernet, mains | Payment authorization: 8 KB, 2/min | 3 s |

- An **access server** is one that end devices attach to: their only way into
  the network, and the server from which the strategy decides for them. A
  **gateway** has an uplink to the cloud, the only way to reach it. A server can
  be both, either or neither.
- A device reaches the network through **one access server at a time**, over a
  radio link, and only a server within `devices.radio_range_m` of it. It stays
  with the one it was placed next to while that server is in range and answers.
  When it is not, the device attaches to the nearest access server in range that
  answers: its service is deployed again from there, and what it had on the old
  radio link is lost. With none in range it is cut off until one comes back. A
  device never relays traffic between two servers.
- Each `<device>` of `edge_devices.xml` names its type and its application,
  `name="Security camera" application="Video analytics"`, and every device of the
  type calls that application. Each application is one **service**.
- The tasks are **stateless**: any server that satisfies the service's traits,
  or the cloud, can run them. By default no service asks for a trait, so any edge
  server can run any service; give one traits (`services.<n>.traits`) to study
  intents.

## Partitions and failures

A partition splits the edge servers into groups for a while:

- every link between two groups is down, and the cloud never relays traffic
  between two servers, so no server or device can reach anything in another
  group;
- at most one group keeps the uplinks of its gateways, and it reaches the cloud
  only from the servers that still have a path to one of them;
- devices stay with their access server and keep sending tasks, so a group
  without access servers holds no devices. A partition does not move a device;
  only a failed server or a move out of radio range does.

Groups are bands of the map (`geo:k`), random draws (`random:k`) or named
(`dc1,dc2|dc3,dc4`, the servers not named forming one more group). Random
failures take down mesh links, gateway uplinks or whole servers, and repair them,
at configurable rates. The routing follows the live topology: a task with no path
fails at once, and one that still had to cross a link when it went down fails
when it would have arrived, both as `FAILED_DUE_TO_NETWORK_OUTAGE`. A link the
transfer has already left can go down without harm.

What a device expects of a task is `tasks.delivery`, or `services.<n>.delivery`
for one service, so one run can mix both:

- `result` (default): the result must travel back, host to access server to
  device, before the deadline. A cut on the way back loses a task that ran.
- `fire_and_forget`: the task has succeeded once it has run on its host before
  the deadline, and no result crosses the network. The device is never told of a
  lost task; the testbed counts what the device never sees.

## Pipelines on the edge

An application of `applications.xml` can be a graph of `<subtask>` stages. By
default every stage returns its result to the device, which sends the next one.
With `services.<n>.pipeline=edge` a stage that has run hands its output to the
next stage from its own host, on the edge: nothing goes back to the device
before the last stage, which follows the delivery mode of the service. This is
the UAV whose video is analysed on the edge and whose detections feed a second
edge task, with only the final track returned to it.

## Mobility

- A **device** moves when its type says so in `edge_devices.xml`
  (`<mobility>true</mobility>`, with any mobility model of the framework).
- An **edge server** moves when `servers.mobile_fraction` or
  `servers.mobile_nodes` says so: a server built into a vehicle. A mesh link to
  a server that moves exists while the two servers are within
  `mesh.link_range_m` and in the same group of every partition in progress, and
  its latency follows their distance.

Every `update_interval` seconds the network follows what moved: mesh links
appear and disappear, and devices out of range of their access server attach to
another one, or are cut off.

## Strategies

A strategy implements `PlacementStrategy`. When a task finds its service not
running, the testbed asks the strategy to deploy it from the device's access
server, makes the task wait the time the strategy says the deployment took, and
sends it to the host it chose. The strategy sees the network only through the
`TestbedWorld`, and cannot tell anything to a server it cannot reach.

```java
public class AccessServerStrategy implements PlacementStrategy {
    public void initialize(TestbedWorld world) { }

    public Deployment deploy(ComputingNode device, EdgeServer home, Service service) {
        Deployment deployment = new Deployment(device, home, service);
        deployment.host = home;          // or another server, or onCloud = true
        deployment.outcome = "LOCAL";
        return deployment;               // deployment.delay: the time it took
    }

    public void release(TestbedWorld.Placement placement) { }
}
```

Beyond placing services, a strategy can override:

| Hook | What it decides or learns |
| --- | --- |
| `route(task, placement)` | where one task runs: per-task scheduling and load balancing. `null` keeps the host of the placement |
| `taskFailed(task, placement)` | whether a failed task ends the placement (the default, whatever the reason) or keeps it |
| `taskCompleted(task, placement)` | a task succeeded |
| `deviceMoved(device, from, to)` | a device attached to another access server |

A strategy can also add columns to the timeline and the deployments file, lines
to the report, and files of its own. It is registered under an algorithm name in
`Testbed.registerDefaults()` and selected by `orchestration_algorithms` in
`simulation_parameters.properties`; several names in that list run one after the
other, on the same continuum, the same failures, the same partitions and the same
tasks from the same devices. The testbed and the failure injector each draw from
their own random source, and a strategy from its own, so what one strategy draws
changes nothing for the others.

`replications=N` runs the sweep N times. Each replication has its own continuum,
failures, partition groups and tasks, the same for every strategy, and
`testbed_summary.csv` gives every figure as a mean over the runs with its
standard deviation and 95% confidence interval.
The class above is in the tests, where it checks the testbed apart from Flocky.

### Flocky

| Flocky (paper) | Here |
| --- | --- |
| Flocky node | a `FlockyAgent` on every edge server |
| Discovery service | Algorithm 1 every `discovery.interval_s`, pinging over the live topology, with the range widened by `AdjustedDistance` |
| Metadata repository | pulls a `NodeSummary` from every reachable known server, and learns its ComponentDefinitions |
| OAM Component / ComponentDefinition | one component per service, with `oam.implementations_per_service` definitions, installed on `oam.seed_nodes_per_definition` servers at the start |
| Swirly + QoE evaluator | reuse a running instance first, then rank by latency (`FLOCKY_LATENCY`, the Legacy evaluator) or by latency, load and green energy (`FLOCKY_SCORED`) |
| Deployment service | the truth on each server: instances, their client counters, their memory; it refuses what a stale summary promised |
| Evolution | a service whose host leaves the view of the device's access server is moved |

A server that stops answering is pinged again for `discovery.forget_after_s`
before it is forgotten, and the bootstrap peers whenever they are unknown, so
that both sides of a healed partition find each other again; `0` gives the
paper's behaviour. Flocky adds discovery accuracy, stale share, known servers and
control traffic to the timeline, and its definition, candidates, API calls,
timeouts, refusals and instance reuse to every deployment.

## Configuration

The settings are in two layers, and the command line is a third:

| Layer | Where | Holds |
| --- | --- | --- |
| Common | `scenarios/common.properties` | what every scenario and every run share: the framework's execution and network (`simulation.<key>`), the testbed's mesh latency, traits, failures, delivery mode, seed, replications and metrics (`testbed.<key>`), and all of Flocky (`flocky.<key>`) |
| Scenario | `scenarios/<scenario>/testbed.properties` and `simulation_parameters.properties` | only what the scenario sets: duration, map, devices, display, edge layer, mobility, services, partitions. Any common key can be set here too, without its prefix, and wins. A `flocky.properties` is read if the scenario has one |
| Command line | `-Dsimulation.<key>`, `-Dtestbed.<key>`, `-Dflocky.<key>` | whatever one run changes; it wins over both |

So a parameter is changed for everything in `common.properties`, for one
scenario in its folder, and for one run on the command line.
`-Dtestbed.common=…` reads another common file.

`testbed.properties` of a scenario and the `testbed.*` keys of the common file
hold the continuum, the services, the tasks,
the failures, the partitions and the metrics; the `flocky.*` keys hold
Flocky. Both document every key. Override a key on the command line with
`-Dtestbed.<key>=…` or `-Dflocky.<key>=…`, and read another file with
`-Dtestbed.config=…` or `-Dflocky.config=…`.

| `testbed.*` key | Default | What it sets |
| --- | --- | --- |
| `edge_nodes` | 40 | number of edge servers |
| `access_node_fraction` / `access_nodes` | 0.6 | the access servers, by share or by name |
| `gateway_fraction` / `gateway_nodes` | 0.15 | the gateways, by share or by name |
| `mesh.link_range_m`, `mesh.min_degree` | 650 m, 2 | density of the mesh; it is always made connected |
| `devices.radio_range_m`, `devices.failover` | 300 m, true | range of a device's radio; whether it leaves an access server that failed |
| `servers.mobile_fraction` / `servers.mobile_nodes` | 0 | the edge servers that move, with `servers.mobility_model` and `servers.speed_mps` |
| `services.<n>.traits` | empty | traits a server must have to run service `n` |
| `services.<n>.delivery` | `tasks.delivery` | `result` or `fire_and_forget`, for one service |
| `services.<n>.pipeline` | `device` | `edge` passes the output of a stage to the next one on the edge |
| `tasks.delivery` | `result` | `result` or `fire_and_forget` |
| `replications` | 1 | number of times the sweep is run |
| `failures.link_failure_rate_per_hour` | 0.5 | per mesh link, with `link_mean_repair_s` |
| `failures.wan_failure_rate_per_hour` | 0.5 | per cloud uplink, with `wan_mean_repair_s` |
| `failures.node_failure_rate_per_hour` | 0 | per server, with `node_mean_repair_s` |
| `partition.<n>.groups` | `geo:2`, `random:3` | `geo:k`, `random:k`, or `dc1,dc2\|dc3,dc4` |
| `partition.<n>.cloud` | `0`, `random` | the one group that keeps its uplinks: its index, `none`, or `random` |
| `seed` | 7 | every random draw of the testbed |

| `flocky.*` key | Default | What it sets |
| --- | --- | --- |
| `discovery.max_distance_ms` | 20 | maximum discovery distance, as a round trip |
| `discovery.interval_s`, `repository.interval_s` | 3, 5 | the periods of the paper's evaluation |
| `swirly.allow_local` | true | whether an access server may host its own devices' services |
| `swirly.cloud_fallback` | true | try the cloud when no edge server accepts |
| `oam.seed_nodes_per_definition` | `all` | servers each definition starts on; a number makes it spread by gossip |

## Run it

From the repository root, after `mvn clean install -DskipTests`:

```bash
mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed"

# fire and forget tasks, to compare with the default
mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed" -Dtestbed.tasks.delivery=fire_and_forget

# make Flocky look beyond the access server
mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed" -Dflocky.swirly.allow_local=false

# a larger edge layer where no group keeps the cloud
mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed" -Dtestbed.edge_nodes=150 \
    -Dtestbed.partition.1.cloud=none -Dtestbed.partition.2.cloud=none

# ten replications, averaged in testbed_summary.csv
mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed" -Dtestbed.replications=10

# the UAV scenario: an edge pipeline, mixed delivery, devices and servers that move
mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed" -Dtestbed.scenario=uav

# the convoy scenario: 16 of 20 edge servers in vehicles, two kinds of UAV
mvn exec:java -Dexec.mainClass="partitiontestbed.Testbed" -Dtestbed.scenario=convoy
```

Everything is a scenario: a folder of `scenarios/`, run by name with
`-Dtestbed.scenario`. Without it the scenario is `default`, a folder like the
others. A scenario holds the four XML files of the framework and the two
properties files with what it sets; the rest is `scenarios/common.properties`.
Its output goes to `output/<scenario>/`, or to
`-Dtestbed.output`. To add one, copy a scenario folder under a new name and
change it; `-Dtestbed.settings=<folder>` runs a scenario kept elsewhere.

The default sweep (40 servers, 90 devices, `FLOCKY_LATENCY` and `FLOCKY_SCORED`,
fifteen minutes each) runs in a few seconds.

## Output

When a partition starts, the run prints what each group holds:

```text
FailureInjector - 300 s: partition 1 [300 s, 600 s) groups=geo:2 cloud=0, 26 links down
FailureInjector -     group 0: 20 servers (10 access, 3 gateways), 38 devices (IoT sensor 15 Security camera 9 Smartphone 9 Payment terminal 5), cloud reachable from 10 of 10 access servers
FailureInjector -     group 1: 20 servers (14 access, 3 gateways), 52 devices (IoT sensor 21 Security camera 14 Smartphone 13 Payment terminal 4), cloud reachable from 0 of 14 access servers
```

At the end of each run, `TestbedSimulationManager` prints how many tasks failed
and why, overall, by device type and in every group of every partition, then the
strategy's own lines. Five files per run land in `output/<scenario>/`, named after the
algorithm, the number of devices and the delivery mode (`mixed` when the services
differ), for example `testbed_FLOCKY_LATENCY_90_devices_result_failures.csv`, with
`_rep<r>` after the mode when the sweep has several replications. One more file,
`testbed_summary.csv`, has a row per strategy and figure (success rates, delay
mean and percentiles, deployments, handovers): runs, mean, standard deviation and
95% confidence interval.

| File | One row per | Columns |
| --- | --- | --- |
| `*_timeline.csv` | `metrics.sample_interval_s` | `active_partitions`, `links_down`, tasks ok and failed in the interval, deployments so far, `handovers` so far, `detached_devices` now, then the strategy's columns (Flocky: `discovery_accuracy`, `stale_share`, `known_per_node`, `migrations`, `control_kbps_per_node`) |
| `*_failures.csv` | failed task | time, device and `device_type`, access server, `partition_group` (`partition:group` or `none`), `reason`, `lost_on` (`request` or `result`), destination |
| `*_device_types.csv` | device type | devices, application, tasks ok and failed, served by edge and by cloud, mean delay and its 50th, 95th and 99th percentiles, `failure_reasons` |
| `*_partitions.csv` | group of a partition | servers, access servers, devices, gateways, access servers with cloud, tasks and deployments while cut, `failure_reasons`, `device_mix`, `members` |
| `*_deployments.csv` | deployment | device and `device_type`, home, service, outcome, host, latency, then the strategy's columns (Flocky: `definition`, `candidates`, `rpcs`, `unreachable`, `refused`, `reused`) |

## Results

The default sweep, fifteen minutes, about 15,100 tasks per run, five
replications (`-Dtestbed.replications=5`). Share of the tasks that succeeded,
mean ± half width of the 95% confidence interval:

| Strategy | `result` | `fire_and_forget` |
| --- | --- | --- |
| `FLOCKY_LATENCY` | 99.98 ± 0.01 % | 99.99 ± 0.01 % |
| `FLOCKY_SCORED` | 99.98 ± 0.02 % | 99.98 ± 0.02 % |
| `FLOCKY_LATENCY`, `swirly.allow_local=false` | 91.4 ± 3.6 % | 96.4 ± 2.2 % |
| `FLOCKY_SCORED`, `swirly.allow_local=false` | 91.1 ± 3.7 % | 96.4 ± 2.2 % |

- With stateless tasks that any server can run, Flocky deploys each device's
  service on or next to the device's own access server, which is always in the
  device's group: the partitions cost almost nothing, with or without the cloud.
- When the access server cannot host (`allow_local=false`), Flocky has to look
  further, and a partition leaves groups without a server it can reach. During
  the partitions 87 ± 6 % of the `result` tasks succeed.
- Requiring a result costs time more than tasks lost to a cut: the 95th
  percentile of the delay is 954 ms against 801 ms without local hosting, and
  the return trip pushes tasks past their deadline that fire and forget
  completes.
- The two evaluators are not told apart by these runs: their intervals overlap
  everywhere. Every strategy of a replication gets the same tasks, so a gap that
  shows up is the strategy's, but read it against the interval.

The UAV scenario (`scenarios/uav/`, twelve minutes, 60 devices, 30 edge servers
of which 30% move, five replications): 88.8 ± 1.9 % of the tasks succeed with
`FLOCKY_LATENCY` and 88.7 ± 2.0 % with `FLOCKY_SCORED`, with 237 ± 29 handovers
per run. Nearly every failed task is lost on the network before it runs, or is
a pipeline stage whose input was: devices out of radio range of every access
server, and tasks on the radio link at a handover. The UAVs, the fastest, lose
the most.

The convoy scenario (`scenarios/convoy/`, ten minutes, 50 devices, 20 edge
servers of which 16 are vehicles that move and 4 fixed base stations, two of
them the only gateways; five replications): 81 ± 11 % of the tasks succeed with
either evaluator, 76 ± 15 % during the partitions, with 201 ± 14 handovers per
run. Scout UAVs run a three stage pipeline on the edge of which only the last
stage returns; mapping UAVs run a two stage pipeline that returns nothing. The
wide interval is the scenario: how well the vehicles happen to cover the area
differs much from one replication to the next, so it needs more replications
than the others before two strategies can be told apart.

## Visualise the deployment

In `simulation_parameters.properties` of the scenario:

```properties
display_real_time_charts=true     # the framework's live charts, plus the deployment map
auto_close_real_time_charts=false # keep the window open at the end
save_charts=true                  # save the charts, and snapshots of the map
```

The deployment map shows the mesh with the links a failure or a partition cut
in red, the gateways' uplinks and the cloud above the map, one line per service
from each access server to the servers that run it for its devices, the servers
by role and those running a service, every device type with its own marker, and,
while a partition lasts, a ring around every server in the colour of its group.
Each redraw is followed by `visualizer.frame_pause_ms` of wall-clock time. With
`save_charts=true` the map is also saved just before and half way through every
partition, and at the end, even without a display.

## Modelling choices

- **Reachability is the live topology's alone.** The framework fails a task
  whose device, orchestrator and destination are further apart than a radio
  range; here the radio links and the mesh links are on the live topology, which
  decides instead.
- **Positions are kept by the second.** When anything moves, `update_interval`
  must be a whole number of seconds, and the network follows at that period.
- **A handover takes no time**, but loses what was on the old radio link, and the
  service is deployed again from the new access server.
- **The mesh of servers that move is by range only.** `mesh.min_degree` and the
  guarantee of a connected mesh hold for the servers that stay put.
- **A stage of an edge pipeline with several inputs** leaves from the host of the
  last input to finish.
- **A sweep that runs its scenarios in parallel** (`parallel_simulation=true`)
  does not give every strategy the same tasks.
- **Each gateway has its own uplink**; `one_shared_wan_network` is ignored.
- **A failed server keeps its state**: it is cut off until it is repaired, and
  the tasks it was running finish.
- **Flocky's rounds take no simulated time.** A discovery or repository round
  runs in one event on the topology of that instant; its messages count as
  traffic. Deployment API calls do take time: round trips, and the RPC timeout
  for a server that cannot be reached.
- **Flocky's resources are memory only**: a deployment reserves the memory of its
  definition, up to `deployment.memory_share` of the server, and an instance
  serves up to `deployment.max_clients_per_instance` devices. An instance whose
  client could not reach it to release it is freed after
  `deployment.orphan_timeout_s`.

## Classes

| Class | Role |
| --- | --- |
| `Testbed` | entry point, strategy registry, replications and their summary, plugs the classes into the simulation |
| `TestbedConfig`, `PropertiesFile` | the `testbed.*` keys; the loader of the three layers |
| `PlacementStrategy`, `Deployment` | what a strategy implements and returns |
| `TestbedWorld`, `EdgeServer`, `Service`, `Trait` | the state of a run a strategy may see, and the attachment of the devices |
| `TestbedSimulationManager` | makes tasks wait for their deployment, handles the delivery modes and the edge pipeline, calls the hooks of the strategy, prints the report |
| `TestbedMetrics` | counters, percentiles and the five CSV files |
| `FailureInjector` | partitions and random failures |
| `LiveTopology`, `TestbedNetworkModel` | routing over the links that are up; transfers lost when a link breaks |
| `TestbedNodesGenerator`, `TestbedTopologyCreator`, `TestbedTaskGenerator` | the continuum, the mesh and the links that appear during the run, the device types |
| `TestbedNode` | a computing node whose links the testbed manages |
| `TestbedOrchestrator` | the orchestrator the framework requires, unused |
| `DeploymentChart`, `TestbedVisualizer` | the deployment map and the live charts |
| `flocky.FlockyStrategy` | Swirly, the evolution step and Flocky's metrics |
| `flocky.FlockyAgent`, `flocky.FlockyRuntime` | discovery, repository and deployment service of a server; their rounds |
| `flocky.FlockyConfig`, `flocky.Oam` | the `flocky.*` keys; definitions and node summaries |
