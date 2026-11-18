# Partition testbed: orchestration on an edge-to-cloud continuum that splits

## What it is

A testbed, built on PureEdgeSim, for asking one question of an orchestration
algorithm: does it keep serving the devices when the network under it splits
into partitions, while the devices keep sending tasks?

The testbed builds an edge-to-cloud continuum of end devices, edge servers and a
cloud data center, sends the devices' stateless tasks through it, and breaks the
network with random failures and scheduled partitions. What decides where the
tasks run, and what happens when something breaks under them (retries, backup
hosts, parallel copies, failure detection), is the **strategy** under test, a
class behind one interface that sees the network only through its own messages.
Every task is judged the same way for every strategy, against the requirement
of its service: on time, or never lost. The first
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
  java/partitiontestbed/flocky/   Flocky, one OrchestrationStrategy
  java/partitiontestbed/mvtora/   MVTORA (Sun et al., TMC 2024)
  java/partitiontestbed/baselines/ ACCESS_SERVER, CLOUD_ONLY, RETRY, REPLICATED
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

Each scenario folder has a README describing it: its devices and services, the
distances, how far radio and mesh links reach, its partitions and its results
([default](scenarios/default/README.md), [uav](scenarios/uav/README.md),
[convoy](scenarios/convoy/README.md)).

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
  radio link is lost. With none in range it is cut off until one comes back.
  Whether a device also talks to other access servers or devices, and whether
  it relays, is the strategy's network policy (below).
- Each `<device>` of `edge_devices.xml` names its type and its application,
  `name="Security camera" application="Video analytics"`, and every device of the
  type calls that application. Each application is one **service**.
- The tasks are **stateless**: any server that satisfies the service's traits,
  or the cloud, can run them. By default no service asks for a trait, so any edge
  server can run any service; give one traits (`services.<n>.traits`) to study
  intents.

## Meshing: which links a strategy uses

What links **exist** is the testbed's, the same for every strategy: the wired
mesh of the edge servers (`mesh.*`), the gateways' uplinks, the radio between a
device and the access servers within `devices.radio_range_m`, the radio between
two devices within `d2d.range_m` (0, the default, gives devices none), and the
links to a moving server within `mesh.link_range_m`. Failures and partitions
take them away.

What links **carry the strategy's traffic** is part of the algorithm's design:
its `OrchestrationStrategy.network()` returns a `NetworkPolicy`, read once when
the topology is built.

| Policy | What it says | Example |
| --- | --- | --- |
| `link(a, b)` | use the links between two layers (`DEVICE`, `EDGE`, `CLOUD`), or within one when `a == b` | `link(DEVICE, DEVICE)`: devices talk to each other directly |
| `link(a, b, false)` | do not use them | `link(EDGE, EDGE, false)`: a strict hierarchy, servers only talk up to the cloud |
| `relay(layer)` | let the nodes of a layer forward traffic for others; a node that does not relay can only start or end a path | `relay(DEVICE)`: multi-hop through devices; `relay(CLOUD)`: the cloud joins two servers |
| `multiHoming(true)` | a device keeps a radio link to every access server in range, not only to its attachment | |
| `bridgeGroups(true)` | a radio contact between two partition groups carries traffic, joining them, where `partitions.cross_group_radio` lets it exist | a drone in range of both groups relays between them |

`NetworkPolicy.hierarchical()` is the default and what every built-in strategy
uses: device to access server, edge mesh, edge to cloud, only edge servers relay.
`NetworkPolicy.deviceMesh()` adds the radio between devices and lets devices
relay. On top of the layer rule, code running on a node can make that node
relay or stop with `ctx.setRelay(node, on)`, for example to elect cluster heads,
and learn which radio contacts it has with `ctx.radioContacts(node)`. Every
message, task and result follows the shortest live path the policy allows, and
is lost when there is none.

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

**Group transfers.** The groups are drawn when the partition starts, but a node
that moves can change group while it lasts (`partitions.group_transfer=true`,
the default):

- a **moving server** in mesh range of no server of its own group, and of a
  server of another group, asks to join the nearest such server's group;
- a **device** whose nearest access server in range is in another group asks to
  attach to it; refused, it tries the next one in range, or stays cut off.

The strategy decides with `admitJoin(node, contact)`; the default admits, and
Flocky admits unless `flocky.discovery.admit_joining=false`. An admitted server
is a member of its new group from then on: its links to the new group come
back, those to its old group go down, and, if it is a gateway, its cloud uplink
follows whether the new group keeps the cloud. A device simply belongs to the
group of its access server. `partitions.group_transfer=false` keeps every node
in the group it was given, and a device then attaches only to access servers of
its own group.

Groups are bands of the map (`geo:k`), random draws (`random:k`) or named
(`dc1,dc2|dc3,dc4`, the servers not named forming one more group). Random
failures take down mesh links, gateway uplinks or whole servers, and repair them,
at configurable rates. The routing follows the live topology: a task with no path
fails at once, and one that still had to cross a link when it went down fails
when it would have arrived, both as `FAILED_DUE_TO_NETWORK_OUTAGE`. A link the
transfer has already left can go down without harm.

What a device expects of a task is `tasks.delivery`, or `services.<n>.delivery`
for one service, so one run can mix both:

- `result` (default): the task is done when its result reaches the device,
  routed to wherever the device is attached by then. A cut on the way back loses
  the result unless the strategy sends it again.
- `fire_and_forget`: the task is done once it has run on a host, and no result
  crosses the network. The device is never told of a lost task; the testbed
  counts what the device never sees.

What the task must achieve to count is the requirement of its service (see "How
tasks are judged"): on time (`deadline`), or done at all within its delivery
window (`lossless`).

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
  its latency follows their distance. A server that drifts away from its group
  can join the group it reaches (see group transfers above).

Every `update_interval` seconds the network follows what moved: mesh links
appear and disappear, and devices out of range of their access server attach to
another one, or are cut off.

## Strategies

A strategy extends `OrchestrationStrategy` and decides everything the testbed
does not: where a task runs, how many times, what happens when something breaks,
and how its nodes talk to each other. The testbed decides nothing for it. It
only gives each strategy the same continuum, failures, partitions and tasks,
and judges every task the same way at the end.

What a strategy sees is its `StrategyContext` (`ctx`), and nothing else:

- **the configuration**: servers, roles, traits, capacities, services, and the
  mesh links each server was given;
- **what is local to a node**: whether a device is attached right now
  (`attachment`), whether a node itself is up (`isUp`);
- **its own messages**: `send` and `rpc`, real transfers on the simulated network,
  which share the links with the tasks, take time, and are lost silently when
  their path breaks. The sender learns of a loss only by its own timeout.

There is no oracle: a strategy cannot ask who can reach whom, at what latency, or
where anything runs. A strategy that wants to know has to ping, gossip or wait
for heartbeats, and pays for it in traffic and in time.

It acts through the same context:

| Action | What it does |
| --- | --- |
| `sendRequest(job, device, server)` | sends a task from its device to a server |
| `dispatch(job, from, host)` | sends a task to a host to run there: one attempt. Several calls run it several times, in parallel or one after another. The host can be a device with a processor, its own for local computing |
| `divide(job, shares...)`, `dispatchPart(job, part, from, host)` | partial offloading: divides a task once into parts, each with its share of the length, input and result, and runs each part as an attempt; the task is done when every part is |
| `sendResult(attempt, from)` | sends a result to the device, wherever it is attached by then |
| `finishAtHost(attempt)` | ends a task that returns nothing, or hands an edge pipeline's output to its next stage |
| `startInstance` / `stopInstance` | deploys or removes a service on a host; memory and startup time are the scenario's, the same for every strategy |
| `cancel(attempt)` | drops an attempt on its host |
| `schedule(delay, action)` | a timer |

And the testbed calls it back at every point where a real system decides:

| Hook | When | Default |
| --- | --- | --- |
| `onTaskCreated(device, job)` | a device creates a task | send it to the access server, lose it when there is none |
| `onTaskArrived(node, job)` | a task reaches a server | none: the strategy must dispatch it |
| `onNoInstance(host, attempt)` | an attempt reaches a host without an instance | it is lost |
| `onExecuted(host, attempt)` | an attempt ran | send the result, or end the task at the host |
| `onResultReceived(device, attempt, first)` | a result reaches the device | nothing |
| `onMessage` / `onRpc` | the strategy's own protocol | nothing |
| `onAttachmentChanged(device, from, to)` | a device hands over, loses every server, or finds one again | nothing |
| `admitJoin(node, contact)` | a node asks to join another partition group | admit |
| `onNodeRestarted(node)` | a crashed server runs again, empty | nothing |

So retries, holding results until they are acknowledged, backup hosts, running
a task on several hosts at once, failure detectors and gossip are all a
strategy's own, defined by its algorithm, and they cost what they cost on the
network and the hosts. The simplest strategy:

```java
public class AccessServerStrategy extends OrchestrationStrategy {
    public void initialize() { }

    public void onTaskArrived(ComputingNode node, Job job) {
        if (!ctx.hasInstance(node, job.service()))
            ctx.startInstance(node, job.service(), null);   // the task waits for it
        ctx.dispatch(job, node, node);
    }
}
```

Seven strategies are registered in `Testbed.registerDefaults()`; a new one is a
class and one more line there, selected by `orchestration_algorithms` in
`simulation_parameters.properties`. Several names in that list run one after the
other on the same continuum, failures, partitions and tasks.

| Algorithm | What it does |
| --- | --- |
| `FLOCKY_LATENCY`, `FLOCKY_SCORED` | Flocky, below |
| `MVTORA` | the offloading game of Sun et al.: local, MEC or vehicle fog computing, below |
| `ACCESS_SERVER` | runs every task on the server it arrives at; no mitigation |
| `CLOUD_ONLY` | sends every task to the cloud |
| `RETRY` | `ACCESS_SERVER` with acknowledgements: the device resends a task, and the host its result, every `baselines.retry.interval_s` until acknowledged, until the deadline (deadline service) or the window (lossless service) |
| `REPLICATED` | runs every task on the access server and on `baselines.replicated.copies` minus one of its mesh neighbours at once; the first result counts |

The testbed, the failure injector and every strategy draw from random sources of
their own, so what one strategy draws changes nothing for the others.
`replications=N` runs the sweep N times, each with its own continuum, failures,
partition groups and tasks, the same for every strategy.

### Flocky

| Flocky (paper) | Here |
| --- | --- |
| Flocky node | a `FlockyAgent` on every edge server |
| Discovery service | Algorithm 1 every `discovery.interval_s`, as RPCs: the round trip of a ping is the distance, a ping without reply within `discovery.rpc_timeout_ms` is out of range, and the range widens with `AdjustedDistance` |
| Metadata repository | asks every known server for its `NodeSummary` by RPC, and for the manifests of the ComponentDefinitions it does not know |
| OAM Component / ComponentDefinition | one component per service, with `oam.implementations_per_service` definitions, installed on `oam.seed_nodes_per_definition` servers at the start |
| Swirly + QoE evaluator | reuse a running instance first, then rank by measured latency (`FLOCKY_LATENCY`, the Legacy evaluator) or by latency, load and green energy (`FLOCKY_SCORED`); each candidate's deployment API is called by RPC until one accepts, then the cloud |
| Deployment service | the truth on each server: instances, their clients, their memory; it refuses what a stale summary promised, and frees a client whose lease its access server stopped renewing |
| Evolution | a service whose host leaves the view of the device's access server is moved; a host that gets a task for an instance it no longer runs tells the sender |

Flocky defines no retry and no backup, so it has none here: a task the network
loses is lost. A server that stops answering is pinged again for
`discovery.forget_after_s` before it is forgotten, and the bootstrap peers
whenever they are unknown, so that both sides of a healed partition find each
other again; `0` gives the paper's behaviour. Flocky adds the servers each one
knows, its deployments and its migrations to the timeline, and writes every
deployment, with its candidates, calls, timeouts and refusals, to
`*_flocky_deployments.csv`.

## How tasks are judged

Every task counts once, however many times a strategy sends or runs it. It is
done when its first result reaches its device, or, for a task that returns
nothing, when it first finishes on a host. At the end of the run it gets one
outcome:

| Outcome | Meaning |
| --- | --- |
| on time | done within its deadline |
| late | done after its deadline, within the delivery window of its service |
| lost | not done within the window |
| censored | not done, and created too close to the end of the run to judge: left out |

Whether that meets the requirement depends on the service
(`services.<n>.requirement`):

- **deadline**: met only on time. Interactive services, which accept losing some
  tasks but not waiting for them.
- **lossless**: met on time or late. Telemetry, payments, maps, where nothing may
  be lost but a delay is acceptable.

A stage of a pipeline that never ran, because the stage before it was never
done, is judged too, so every strategy is judged on the same tasks. The figures
are the same for every algorithm, which is what makes them comparable:

| Figure | What it says |
| --- | --- |
| `requirement_met_pct` | the share of the tasks that met the requirement of their service; also during partitions, outside them, per requirement and per service, with `services_on_target` against `services.<n>.target_pct` |
| `on_time_pct`, `late_pct`, `lost_pct` | how the tasks ended |
| `delay_p50_ms`, `delay_p95_ms`, `delay_p99_ms` | the time from creation to done |
| `attempts_per_task`, `redundant_runs_pct` | how often tasks ran, and how much of that was wasted |
| `instances_started`, `instance_memory_mb_per_server` | what the deployments held |
| `control_kbps_per_server`, `control_messages_lost` | what the strategy's own protocol cost |
| `exposed_<kind>_tasks`, `exposed_<kind>_met_pct` | how many tasks met each kind of trouble while not done, and how many of those still met their requirement: `device_detached` (out of range at creation or after), `handover`, `group_change`, `host_failed`, `partition_started` |

## Configuration

The settings are in two layers, and the command line is a third:

| Layer | Where | Holds |
| --- | --- | --- |
| Common | `scenarios/common.properties` | what every scenario and every run share: the framework's execution and network (`simulation.<key>`), the testbed's mesh latency, traits, failures, delivery mode, seed, replications and metrics (`testbed.<key>`), all of Flocky (`flocky.<key>`) and the baselines (`baselines.<key>`) |
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
| `tasks.requirement`, `services.<n>.requirement` | `deadline` | `deadline` (on time, losses accepted) or `lossless` (done at all, late or not) |
| `tasks.delivery_window_s`, `services.<n>.delivery_window_s` | 60 | how long after its creation a task still counts as delivered |
| `services.<n>.target_pct` | none | the share of a service's tasks that must meet its requirement |
| `instances.memory_mb`, `instances.startup_ms` (or per service) | 512, 500 | what an instance holds and how long it takes to start, for every strategy |
| `replications` | 1 | number of times the sweep is run |
| `failures.link_failure_rate_per_hour` | 0.5 | per mesh link, with `link_mean_repair_s` |
| `failures.wan_failure_rate_per_hour` | 0.5 | per cloud uplink, with `wan_mean_repair_s` |
| `failures.node_failure_rate_per_hour` | 0 | per server, with `node_mean_repair_s` |
| `failures.node_failure_mode` | `isolate` | `isolate`: a failed server is cut off and keeps its state; `crash`: it loses what ran on it and its instances |
| `partition.<n>.groups` | `geo:2`, `random:3` | `geo:k`, `random:k`, or `dc1,dc2\|dc3,dc4` |
| `partition.<n>.cloud` | `0`, `random` | the one group that keeps its uplinks: its index, `none`, or `random` |
| `partitions.group_transfer` | `true` | whether a node that moves can join another group while a partition lasts |
| `seed` | 7 | every random draw of the testbed |

| `flocky.*` key | Default | What it sets |
| --- | --- | --- |
| `discovery.max_distance_ms` | 20 | maximum discovery distance, as a round trip |
| `discovery.admit_joining` | `true` | whether a node of another partition group that comes in range is admitted |
| `deployment.orphan_timeout_s` | 60 | how long a host keeps a client whose access server stopped renewing its lease |
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

At the end of each run, `TestbedSimulationManager` prints the figures above:
requirement met overall and per service, outcomes, delays, the tasks that met
each kind of trouble, what the strategy cost, then the strategy's own lines and
every group of every partition. Four files per run land in `output/<scenario>/`,
named after the algorithm, the number of devices and the delivery mode (`mixed`
when the services differ), for example
`testbed_FLOCKY_LATENCY_90_devices_result_tasks.csv`, with `_rep<r>` after the
mode when the sweep has several replications. Two more cover the sweep:
`testbed_summary.csv`, one row per algorithm and figure with its runs, mean,
standard deviation and 95% confidence interval, and `testbed_kpis.csv`, one row
per algorithm with the main figures and the half width of their interval, side
by side.

| File | One row per | Columns |
| --- | --- | --- |
| `*_tasks.csv` | task | device and `device_type`, service and stage, `requirement`, created, done, delay, `outcome`, `met`, attempts, duplicates, the host that did it, whether a partition was on, `exposures` |
| `*_timeline.csv` | `metrics.sample_interval_s` | tasks created in the interval and the share that met their requirement or was lost; `active_partitions`, `links_down`, `handovers` so far, `detached_devices`, instances and their memory, control traffic; then the strategy's columns (Flocky: `known_per_node`, `deployments`, `migrations`) |
| `*_device_types.csv` | device type | devices, service, requirement, tasks, censored, requirement met, on time, late, lost, delay mean and percentiles, done on edge or cloud, attempts, duplicates |
| `*_partitions.csv` | group of a partition | servers, access servers, devices, gateways, access servers with cloud (all when the partition started), the tasks created in it while it lasted with the share met and lost, `servers_joined`, `servers_left`, `devices_joined`, `devices_left`, `device_mix`, `members` |

## Results

Five replications of every scenario with the six registered strategies
(`-Dtestbed.replications=5`). Requirement met: the share of the tasks that met
the requirement of their service, mean with the half width of its 95%
confidence interval.

| Algorithm | default | uav | convoy | Cost |
| --- | --- | --- | --- | --- |
| `RETRY` | 99.85 ± 0.00 % | **91.3 ± 1.5 %** | **85.1 ± 10.0 %** | acknowledgements, 0.2–0.3 kbit/s per server |
| `REPLICATED` | **99.89 ± 0.02 %** | 89.9 ± 1.4 % | 83.8 ± 10.2 % | 1.6–2 runs per task, 34–47 % redundant |
| `ACCESS_SERVER` | 99.85 ± 0.00 % | 89.8 ± 1.4 % | 83.8 ± 10.2 % | none |
| `FLOCKY_LATENCY` | 99.84 ± 0.01 % | 89.7 ± 1.4 % | 82.7 ± 9.7 % | 14–28 kbit/s per server of discovery and repository traffic |
| `FLOCKY_SCORED` | 99.83 ± 0.03 % | 89.6 ± 1.3 % | 83.1 ± 10.1 % | the same |
| `CLOUD_ONLY` | 69.1 ± 3.3 % | 66.0 ± 0.6 % | 40.8 ± 6.0 % | none at the edge |

- Every strategy runs on the same tasks, so the differences are best read
  paired by replication: `RETRY` meets 1.5 ± 1.0 (uav) and 1.3 ± 0.6 (convoy)
  points more than `ACCESS_SERVER`, in every replication; Flocky meets as many
  as `ACCESS_SERVER` in default and uav, and 1.1 ± 1.0 fewer in convoy.
- Keeping each device's tasks on its own access server is what keeps the edge
  strategies serving through a partition: the server is always in the device's
  group. `CLOUD_ONLY` loses every task of a group without the cloud.
- In the mobile scenarios most losses are devices out of range of every server,
  and results whose device has moved on. Only a strategy that keeps and resends
  tasks and results recovers them: under `RETRY`, 84–88 % of the tasks whose
  device handed over in flight still meet their requirement, against 30–33 %
  without. A copy on another host (`REPLICATED`) helps with a host that fails,
  which is rare next to radio losses, and mainly shortens the tail of the delay.
- Letting a node join the group it reaches (`partitions.group_transfer`) gains
  Flocky 6.3 ± 1.8 (uav) and 10.7 ± 3.8 (convoy) points during the partitions,
  against fixed groups.

Each scenario's README has its own table, by service and by edge case.

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
- **A handover takes no time**, but loses what was on the old radio link. What
  becomes of the tasks of a device that moves is the strategy's: a result is
  routed to wherever the device is attached when it is sent.
- **The mesh of servers that move is by range only.** `mesh.min_degree` and the
  guarantee of a connected mesh hold for the servers that stay put.
- **A stage of an edge pipeline with several inputs** leaves from the host of the
  last input to finish.
- **A sweep that runs its scenarios in parallel** (`parallel_simulation=true`)
  does not give every strategy the same tasks.
- **Each gateway has its own uplink**; `one_shared_wan_network` is ignored.
- **A failed server keeps its state** by default: it is cut off until it is
  repaired, and the tasks it was running finish. With
  `failures.node_failure_mode=crash` it loses them, and its instances.
- **Control traffic is real.** Every message of a strategy is a transfer on the
  network, as tasks and results are: it takes the store-and-forward time of its
  path, shares the bandwidth of its links, and is lost when its path breaks.
- **Instances cost the same for every strategy**: their memory and startup time
  are the scenario's, and an instance only starts on a server whose traits the
  service requires. Flocky lends at most `deployment.memory_share` of a server to
  its instances, and an instance serves up to
  `deployment.max_clients_per_instance` devices.
- **A task is judged once, at the end.** The run ends at `simulation_time`
  whatever is still on its way; a task that cannot be judged by then is censored.

### MVTORA

MVTORA is the MEC-VFC-aided task offloading and resource allocation of Sun et
al., *Joint Task Offloading and Resource Allocation in Aerial-Terrestrial UAV
Networks With Edge and Fog Computing for Post-Disaster Rescue*, IEEE TMC 23(9),
2024. Its keys are `mvtora.*` in `common.properties`; a key left out takes the
value of Table II of the paper.

| MVTORA (paper) | Here |
| --- | --- |
| C-UAV | any device; it computes locally when it has a processor |
| E-UAV: MEC server and controller | the access server the device is attached to |
| Rescue vehicle: fog node | a moving edge server the device hears by radio (`vfc.fog_nodes=mobile`), or any server it hears other than its controller (`any`) |
| State information collected each slot | fog nodes beacon their idle cores, backlog and position every `beacon.interval_s`; a device sends its controller a request with its task, its processor and the fog nodes it hears |
| Time slot | a round starts with the first request a controller gets and decides for all requests that came within `round.collect_ms` |
| Algorithm 1, offloading game | better responses until no device changes, at the controller |
| Algorithm 2, MEC allocation | bisection with the closed form of eq. 30, over the controller's idle cores, one core at most per task |
| Algorithms 3 and 4, VFC | the `vfc.max_fog_nodes` preferred fog nodes (eq. 33), and the division of the task among them by the genetic algorithm; one fog node per device per round (22g) |
| CSI | the nominal bandwidth of the device's radio (`rate.model=nominal`), or the LoS model of eq. 6 to 10 from the reported positions (`channel`) |

What the paper does not define is settled as it would: a device out of range of
every access server, or with no decision after `round.decision_timeout_ms`,
computes locally (without a processor, it sends the task to its controller); a
pipeline stage runs where its input is; a stage that hands over on the edge
goes to one fog node whole; a host without an instance starts one. There is no
retry. The controller's allocation drives the decisions, while the testbed runs
each task on one core, as for every strategy.

Two things follow from the paper's model and are worth knowing when reading the
results. The local energy `k f^3 T` is large at the speeds of the testbed's
devices (a UAV's 8 GHz-equivalent core), so a device with a controller in range
practically never computes locally. A device out of range does, which no other
strategy does: in the UAV scenario, most of MVTORA's advantage comes from tasks
created while out of range (`device_detached`). MVTORA adds its decisions by way
to the timeline (`mvtora_local`, `mvtora_mec`, `mvtora_vfc`, `mvtora_timeouts`).

## Classes

| Class | Role |
| --- | --- |
| `Testbed` | entry point, strategy registry, replications and their summary, plugs the classes into the simulation |
| `TestbedConfig`, `PropertiesFile` | the `testbed.*` keys; the loader of the three layers |
| `OrchestrationStrategy`, `StrategyContext` | what a strategy implements, and all it may see and do |
| `Job`, `Attempt` | a task as it is judged, and one run of it on a host |
| `TaskEngine` | carries out what the strategy decides: messages, RPCs, timers, attempts, instances; keeps the ledger of every task |
| `Carrier` | what the network carries: a message, a task or a result |
| `TestbedWorld`, `EdgeServer`, `Service`, `Trait` | the state of a run, the attachment of the devices, the partition groups |
| `TestbedSimulationManager` | hands the tasks of the devices to the strategy, takes the runs of the framework's nodes back, prints the report |
| `TestbedMetrics` | the judgement of every task, the figures and the CSV files |
| `FailureInjector` | partitions and random failures |
| `LiveTopology`, `TestbedNetworkModel` | routing over the links that are up; carriers lost when a link breaks |
| `NetworkPolicy` | which links a strategy uses, which nodes relay, multi-homing, bridging of groups |
| `TestbedNodesGenerator`, `TestbedTopologyCreator`, `TestbedTaskGenerator` | the continuum, the mesh and the links that appear during the run, the device types |
| `TestbedNode` | a computing node whose links the testbed manages |
| `TestbedOrchestrator` | the orchestrator the framework requires, unused |
| `baselines.*` | `ACCESS_SERVER`, `CLOUD_ONLY`, `RETRY`, `REPLICATED`, and their `baselines.*` keys |
| `DeploymentChart`, `TestbedVisualizer` | the deployment map and the live charts |
| `flocky.FlockyStrategy` | Swirly, the evolution step and Flocky's metrics |
| `flocky.FlockyAgent` | discovery, repository and deployment service of a server, as RPCs |
| `flocky.FlockyConfig`, `flocky.Oam` | the `flocky.*` keys; definitions and node summaries |
| `mvtora.MvtoraStrategy` | MVTORA's protocol: beacons, requests, rounds at the controllers, decisions |
| `mvtora.Mvtora` | its models and Algorithms 1 to 4, free of the testbed |
| `mvtora.MvtoraConfig` | the `mvtora.*` keys |
