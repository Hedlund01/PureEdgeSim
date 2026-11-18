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
4. **Strategy.** `OrchestrationStrategy.initialize` creates the strategy's state,
   starts its timers and any instances it wants from the start.

Because the tasks and the failure times are drawn before or independently of
what the strategy does, every strategy of a replication faces identical
conditions.

## The life of one task

A task is moved along by events, and at every point where a real system decides,
the testbed asks the strategy. With `delivery=result` and the default behaviour
of each hook:

| Step | What happens | Who decides | Time it takes |
| --- | --- | --- | --- |
| 1. Created | At its arrival time the device creates the task | `onTaskCreated`: by default send it to the access server, lose it if out of range | none |
| 2. Sent | The request crosses the radio link | | transfer |
| 3. Arrived | The task is at a server | `onTaskArrived`: dispatch it to one host or several, now or later | none, unless the strategy waits |
| 4. Dispatched | Each attempt travels to its host | | transfer over the mesh, or none if the host is the server itself |
| 5. Queued | The attempt waits for its instance to start, then for a free core | `onNoInstance` if the host runs none | startup time, waiting time |
| 6. Executed | One attempt occupies one core | | length in MI divided by the core's MIPS |
| 7. Returned | The result goes to the device, routed to wherever it is attached now | `onExecuted`: send it, hold it, send it again | transfer |
| 8. Done | The first result to reach the device makes the task done | `onResultReceived` | none |

With `fire_and_forget` the task is done at step 6, when the strategy finishes it
at its host. A strategy can also run a task on its own device, which crosses no
network and runs on a core of the device, or divide it into parts that run on
several hosts at once (partial offloading): each part carries its share of the
input, length and result, and the task is done when the last part is. In an edge pipeline a finished stage releases the next one at its
host (step 3 of the next stage) instead of returning.

Anything on the network can be lost: no path when it is sent, or a link of its
path that breaks before it arrives. Nobody is told; the sender finds out by its
own timeout, if the strategy has one. An attempt is also lost when its host has
no instance, or when its host crashes (`failures.node_failure_mode=crash`).
Whether a lost task is sent again, run elsewhere, or run on several hosts from
the start is the strategy's choice: the testbed only counts.

Every task is judged once, at the end of the run, on the time from its creation
to the moment it was done: on time, late (within the delivery window of its
service), lost, or censored. A `deadline` service needs it on time, a
`lossless` one needs it done at all. A second result of the same task is a
duplicate and changes nothing but the cost.

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
  changes. Retransmission, when there is any, is the strategy's.
- Everything is a transfer: tasks, results, and the strategy's own messages and
  RPCs, which compete for the same bandwidth.

### Failures, partitions and reachability

- `LiveTopology` is the graph with the unusable links hidden: links cut by a
  partition or a random failure, links of a failed server, and radio links that
  are out of use or out of range. Paths are recomputed after every change, from
  each source over a view that also hides what the policy does not route over.
- A strategy never sees `LiveTopology`. It learns what it knows from its own
  messages, as a deployed system would.
- A **partition** is two events: at its start the links between the groups are
  cut, at its end they are restored. Random failures are pairs of fail and
  repair events with exponentially distributed times.
- Between the two, membership is checked on the 1 s mobility step: a moving
  server out of mesh range of its whole group, and in range of another, asks the
  strategy to join (`admitJoin`). Admitted, the partition restores its links to
  the new group and cuts those to the old one in the same step, so no path ever
  joins two groups as they are now. A device's group is its access server's.
- What a path may use is the strategy's `NetworkPolicy`: the links between
  the layers it uses, through the nodes that relay (by layer, or set on a node
  with `setRelay`). A node that does not relay can start or end a path, never be
  a hop. The default policy relays on edge servers only, so the cloud and the
  devices never bridge two groups.
- A link between two groups of a partition in progress is also kept out of the
  routes by a barrier, unless it is a radio link (to a device or a moving
  server), `partitions.cross_group_radio` is on, and the policy bridges groups.
  With `cross_group_radio` on, the partition cuts only the wired links between
  its groups; the radio contacts follow their range.
- A transfer that still had to cross a link when it went down is lost at the
  moment it would have arrived.

### Compute

- A server is a number of cores, a speed per core and memory. An attempt uses
  one core for `length / MIPS` seconds. There is no time slicing and no
  interference between attempts.
- A service runs on a host only in an instance, which holds the memory of the
  service and takes its startup time, the same for every strategy, and only on
  a server with the traits the service requires.
- A failed server is unreachable. With `isolate` it keeps its state and the
  attempts already running finish; with `crash` they and its instances are lost,
  and the strategy is told when the server runs again.

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

A strategy is ordinary Java code called at the decision points of a task
(`onTaskCreated`, `onTaskArrived`, `onExecuted`, ...). A call takes no simulated
time; what takes time is what the strategy does: its messages and RPCs cross the
network, its timers fire later, its instances take their startup time. Periodic
work, such as Flocky's discovery rounds, is a chain of timers. A strategy sees
only its `StrategyContext`: the configuration, what is local to a node, and its
own messages. It cannot read the live topology, so a failure detector, a view of
the neighbours or a placement map costs it real traffic and real delay.

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
conditions, on figures that are the same for every strategy: the share of tasks
that meet the requirement of their service, what is lost, how delay
distributions shift, what the strategy's protocol and redundancy cost, and how
the tasks that met a detached device, a failed host or a partition fared.

Be careful with:

- **Absolute delays.** Transfers are quantised to 0.05 s per hop, arrivals to
  whole seconds, and positions to one second.
- **Message sizes.** A strategy's messages compete with tasks for bandwidth, but
  their sizes are what the strategy declares.
- **Transient network behaviour.** Routing finds a new path the moment a link
  breaks (the strategy does not), and a handover takes no time.
- **Single runs.** One seed is one sample; use `testbed.replications` and read
  the confidence intervals in `testbed_summary.csv`.

## Where to look in the code

| Topic | Class |
| --- | --- |
| Event loop, clock, queue | `simulationengine.PureEdgeSim`, `FutureQueue`, `Event`, `SimEntity` |
| Task life cycle | `partitiontestbed.TaskEngine` (what the strategy does) and `TestbedSimulationManager` (the glue to the framework) |
| What a strategy sees and does | `partitiontestbed.OrchestrationStrategy`, `StrategyContext`, `Job`, `Attempt` |
| How tasks are judged | `partitiontestbed.Job.outcome`, `TestbedMetrics` |
| Transfers and bandwidth sharing | `network.NetworkLink`, `network.DefaultNetworkModel`, `partitiontestbed.TestbedNetworkModel` |
| Reachability | `partitiontestbed.LiveTopology` |
| Partitions and failures | `partitiontestbed.FailureInjector` |
| Execution on a node | `datacentersmanager.DefaultComputingNode` |
| Task generation | `taskgenerator.DefaultTaskGenerator`, `partitiontestbed.TestbedTaskGenerator` |
| Mobility | `locationmanager.MobilityModel`, `TestbedWorld.updateAttachments`, `TestbedTopologyCreator.updateMesh` |
| Seeds | `distribution.Randomness`, `TestbedWorld.seed` |

Framework classes are under `PureEdgeSim/main/java/com/mechalikh/pureedgesim/`,
testbed classes under `PureEdgeSim/testbed/java/partitiontestbed/`.
