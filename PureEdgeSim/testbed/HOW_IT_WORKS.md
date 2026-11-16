# How the simulator works

This explains what kind of simulator the partition testbed is, what happens
inside a run, and how far its models can be trusted. For what to configure and
how to run it, see [README.md](README.md).

## In one paragraph

The testbed runs on PureEdgeSim, a **discrete-event simulator**. Nothing is
executed for real: there are no containers, no packets and no threads per node.
The simulator keeps a clock and a queue of future events, jumps from one event
to the next, and lets the object each event belongs to react. A run of fifteen
simulated minutes with 90 devices takes about a second of real time. One run is
deterministic: the same settings and seeds give the same result.

## The event loop

| Piece | What it is | Where |
| --- | --- | --- |
| Clock | simulated seconds, a `double`, starting at 0 | `PureEdgeSim.clock()` |
| Event | a time, a target entity, a tag saying what happens, and optional data such as a task | `Event` |
| Future queue | every scheduled event, sorted by time; two events at the same time run in the order they were scheduled | `FutureQueue` |
| Entity | anything that receives events: the simulation manager, every computing node, every network link, the network model, the failure injector, a strategy's own runtime | `SimEntity` |

The loop (`PureEdgeSim.start()`):

1. Every entity is told the simulation starts, and schedules its first events.
2. The earliest event is taken from the queue, the clock is set to its time, and
   its entity's `processEvent` runs. That usually schedules more events.
3. Step 2 repeats until the queue is empty or the manager ends the run.
4. Every entity is told the simulation ended; the testbed writes its report and
   files.

Time only moves when an event is taken. Between two events nothing happens, and
an event takes no simulated time to process: anything that should take time is
modelled by scheduling a later event.

Most of the run is purely event-driven. Three things are **time-stepped**, which
means an event that re-schedules itself at a fixed period:

| What | Period | Setting |
| --- | --- | --- |
| Progress of transfers on a link that is carrying something | 0.05 s | `simulation.network_update_interval` |
| Position of what moves, idle energy, and the testbed's mobility tick | 1 s | `simulation.update_interval` |
| A row of the timeline file | 10 s | `testbed.metrics.sample_interval_s` |

## What is built before the first event

Each iteration of a sweep (one strategy, one device count, one replication)
builds a fresh world, in this order:

1. **Nodes.** The cloud, the edge servers (drawn from the template of
   `edge_datacenters.xml`, placed at random, given their roles and traits) and
   the devices (placed next to an access server).
2. **Topology.** A graph whose vertices are nodes and whose edges are directed
   network links: the mesh between edge servers, one uplink and downlink per
   gateway to the cloud, and one radio link pair per device.
3. **Tasks.** *Every task of the whole run is generated up front*: for each
   device, arrival times are drawn from its application's distribution, and each
   task gets its size, length and deadline. Arrival times are whole seconds.
4. **Strategy.** `PlacementStrategy.initialize` creates the strategy's state.

Because the tasks and the failure times are drawn before or independently of
what the strategy does, every strategy of a replication faces identical
conditions.

## The life of one task

A task is moved along by events. With `delivery=result`:

| Step | What happens | Time it takes |
| --- | --- | --- |
| 1. Sent | At its arrival time the device sends the request to its access server over its radio link | transfer |
| 2. Placed | If the device's service is not placed, the strategy's `deploy` is called. The task, and those that arrive meanwhile, wait | the delay the strategy returns |
| 3. Routed | `route` may pick a host for this one task; otherwise it goes to the placement's host | transfer over the mesh, or none if the host is the access server |
| 4. Queued | The host starts it if a core is free, otherwise it waits in a first-come queue | waiting time |
| 5. Executed | One task occupies one core | task length in MI divided by the core's MIPS |
| 6. Returned | The result goes host to access server to device | two transfers |
| 7. Judged | The task fails if its total delay exceeds its latency, or the request's deadline has passed | none |

With `fire_and_forget`, the task is judged right after step 5 and nothing is
sent back. In an edge pipeline, a stage that finishes step 5 sends the next
stage directly from its host (step 3 of the next stage) instead of returning.

A task can fail at any step: no path at step 1, 3 or 6 (network outage), no
host at step 2, not enough memory or storage at step 4, or late at step 7.

The delay a task is judged on is the sum of its network time, its waiting time
and its execution time; the deployment wait of step 2 counts as network time.

## The models

### Network

- A **transfer** follows the shortest live path, by latency, hop by hop. It must
  finish one link before it enters the next (store and forward).
- On a link, the **bandwidth is shared equally** between the transfers currently
  on it. Every 0.05 s each transfer advances by its share. When it is done, the
  link's latency is added and it moves to the next link.
- So the *time of the arrival event* is rounded up to the 0.05 s step on each
  hop, while the *network time recorded on the task* is the exact figure. A hop
  therefore costs at least one step of simulated time even for a tiny message.
- There are no packets, no retransmissions, no congestion control and no
  routing protocol: the path is recomputed from the true topology whenever it
  changes.

### Failures, partitions and reachability

- `LiveTopology` is the graph with the unusable links hidden: links cut by a
  partition or a random failure, links of a failed server, and radio links that
  are out of use or out of range. Paths are recomputed after every change.
- A **partition** is two events: at its start the links between the groups are
  cut, at its end they are restored. Random failures are pairs of fail and
  repair events with exponentially distributed times.
- The cloud and the devices are never intermediate hops, so they cannot bridge
  two groups.
- A transfer that still had to cross a link when it went down is failed at the
  moment it would have arrived.

### Compute

- A server is a number of cores, a speed per core, memory and storage. A task
  uses one core for `length / MIPS` seconds and holds its container size in
  memory while it runs. There is no time slicing, no interference between tasks
  and no start-up cost unless the strategy charges one in its deployment delay.
- A failed server is unreachable but keeps its state: tasks already running on
  it finish.

### Mobility

- The path of everything that moves is **computed before the run**, one position
  per second, by the framework's mobility model.
- Every second the testbed's mobility tick reads the current positions, enables
  or disables mesh links to moving servers by range, updates their latency, and
  re-attaches devices that lost their access server.

### Energy

The framework accounts idle energy every second and dynamic energy per task and
per transfer, and a battery device dies when its battery is empty. The testbed
reports none of this itself; it is in the framework's own log.

### The strategy under test

A strategy is ordinary Java code called at decision points (`deploy`, `route`,
`taskFailed`). The call itself takes no simulated time: the strategy *states*
how long its decision took. For periodic work, such as Flocky's discovery
rounds, a strategy creates its own entity and schedules its own events. A
strategy reads the network through `TestbedWorld`; whether it only uses what a
real node could know is up to its implementation.

## Randomness and repeatability

| Source | Seeded by | Draws |
| --- | --- | --- |
| Framework | `simulation.random_seed` | node hardware, mobility paths, task arrivals and sizes |
| Testbed continuum | `testbed.seed` | server positions, roles, traits, device positions |
| Failure injector | derived from `testbed.seed` | failure and repair times, partition groups |
| Strategy | derived from `testbed.seed` | whatever the strategy draws |

The sources are separate so that what one strategy draws cannot change the
conditions of another. Replication `r` moves all of them by `r`. The framework
source is only identical across strategies when the sweep runs sequentially
(`simulation.parallel_simulation=false`).

## What this means for the results

Trust it for comparing strategies on **relative** behaviour under identical
conditions: who keeps serving during a partition, how often services are
redeployed, how delay distributions shift.

Be careful with:

- **Absolute delays.** Transfers are quantised to 0.05 s per hop, arrivals to
  whole seconds, and positions to one second.
- **Control-plane cost.** A strategy's messages cost only what the strategy
  declares; they do not compete with tasks for bandwidth.
- **Transient network behaviour.** Detection of a broken path is instantaneous
  for routing, and a handover takes no time.
- **Single runs.** One seed is one sample; use `testbed.replications` and read
  the confidence intervals in `testbed_summary.csv`.

## Where to look in the code

| Topic | Class |
| --- | --- |
| Event loop, clock, queue | `simulationengine.PureEdgeSim`, `FutureQueue`, `Event`, `SimEntity` |
| Task life cycle | `simulationmanager.DefaultSimulationManager`, extended by `partitiontestbed.TestbedSimulationManager` |
| Transfers and bandwidth sharing | `network.NetworkLink`, `network.DefaultNetworkModel`, `partitiontestbed.TestbedNetworkModel` |
| Reachability | `partitiontestbed.LiveTopology` |
| Partitions and failures | `partitiontestbed.FailureInjector` |
| Execution on a node | `datacentersmanager.DefaultComputingNode` |
| Task generation | `taskgenerator.DefaultTaskGenerator`, `partitiontestbed.TestbedTaskGenerator` |
| Mobility | `locationmanager.MobilityModel`, `TestbedWorld.updateAttachments`, `TestbedTopologyCreator.updateMesh` |
| Seeds | `distribution.Randomness`, `TestbedWorld.seed` |

Framework classes are under `PureEdgeSim/main/java/com/mechalikh/pureedgesim/`,
testbed classes under `PureEdgeSim/testbed/java/partitiontestbed/`.
