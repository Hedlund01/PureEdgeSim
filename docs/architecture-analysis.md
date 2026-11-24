---
description: "Analysis of PureEdgeSim's architecture and extension points, and how the Edge SDK adds fault injection, per-link channels and network partitions without modifying the core."
---
# Architecture analysis for the Edge SDK

This document records the analysis done before building the Edge SDK
(`edgesdk/`). It covers four things:

- how PureEdgeSim is put together;
- where it can be extended;
- what the SDK needs that the extension points cannot express;
- how the SDK closes each gap **without modifying a single line of the core**,
  which is everything under `PureEdgeSim/main/`.

For the general architecture of the simulator, see [Architecture](architecture.md) and [Task lifecycle](task-lifecycle.md).

## 1. Architecture map

```mermaid
flowchart TD
  Sim["Simulation.launchSimulation()<br/>checkFiles() / loadScenarios()"] --> Thread["SimulationThread.startSimulation()"]
  Thread -->|reflection ctor<br/>(SimLog, PureEdgeSim, int, int, Scenario)| SM["SimulationManager<br/>(DefaultSimulationManager)"]
  Thread -->|loadModels| NM["NetworkModel(SimulationManager)<br/>DefaultNetworkModel.send()"]
  Thread --> DCM["DataCentersManager"]
  DCM --> GEN["ComputingNodesGenerator<br/>(DefaultComputingNodesGenerator reads XML)"]
  DCM --> TOPO["TopologyCreator → InfrastructureGraph<br/>(jgrapht, Dijkstra, pathsMap cache)"]
  GEN --> NODE["ComputingNode (DefaultComputingNode)<br/>+ MobilityModel + EnergyModelComputingNode"]
  TOPO --> LINK["NetworkLink subclasses (Wifi/Cellular/Ethernet/Man/Wan)<br/>LinkProfile, TransferProgress, EnergyModelNetworkLink"]
  Thread --> TG["TaskGenerator → FutureQueue&lt;Task&gt;"]
  Thread --> ORCH["Orchestrator.findComputingNode(layers, task)"]
  ENGINE["simulationengine: PureEdgeSim clock, FutureQueue, Event, SimEntity,<br/>OnSimulationStart/EndListener"] -.drives.-> SM & NM & LINK & NODE
```

| Component | Core classes | Role |
| --- | --- | --- |
| Event engine | `simulationengine.PureEdgeSim`, `SimEntity`, `Event`, `FutureQueue`, `OnSimulationStartListener`, `OnSimulationEndListener` | A discrete-event loop. Entities schedule events (`schedule(entity, delay, tag, data)`), and the loop takes the earliest one, moves the clock and calls `processEvent`. Events at the same time run in insertion order. `terminate()` stops the loop. |
| Simulation manager | `SimulationManager`, `DefaultSimulationManager` | Owns the task life cycle: it sends tasks to the orchestrator, then to the executing node, and returns the results. It also judges failures (`taskFailed(task, phase)`) and logs. |
| Data centers and devices | `DataCentersManager`, `ComputingNodesGenerator`, `DefaultComputingNode`, `LocationAwareNode`, `EnergyAwareNode`, `NetworkingNode` | Nodes with cores, RAM, storage, a mobility model and an energy model. Execution is `submitTask`, then `EXECUTION_FINISHED`, then `TRANSFER_RESULTS_TO_ORCH` to the manager. |
| Network | `NetworkModel`, `DefaultNetworkModel`, `NetworkLink` (+ `Wifi`, `Cellular`, `Ethernet`, `Man`, `Wan` subclasses), `InfrastructureGraph`, `TransferProgress` | Directed links in a jgrapht graph. Bandwidth is shared equally between transfers and advanced every `network_update_interval`; transfers are store-and-forward. |
| Mobility | `MobilityModel` and its models (random walk, Gauss-Markov, waypoint, group, Lévy, Manhattan, …) | Every path is precomputed at simulation start (`generatePath`), one position per `update_interval`. |
| Workload | `TaskGenerator`, `DefaultTaskGenerator`, `Application` | All tasks are generated before the run into a `FutureQueue`. |
| Orchestration | `Orchestrator`, `DefaultOrchestrator` | `findComputingNode(layers, task)` chooses an index in a candidate list built from the architecture (`CLOUD_ONLY`, `EDGE_AND_CLOUD`, `ALL`, …). |
| Energy | `EnergyModelComputingNode`, `EnergyModelNetworkLink` | Idle energy is counted every `update_interval`, dynamic energy per task (linear in load), and network energy per bit by connectivity type. A battery node dies when its battery is empty. |
| Randomness | `distribution.Randomness`, `AbstractDistribution` | One seeded stream per thread and scenario. Distributions accept their own `Random`. |

## 2. Extension points

| Mechanism | What it allows |
| --- | --- |
| `SimulationAbstract.setCustomSimulationManager / ComputingNode / ComputingNodesGenerator / TopologyCreator / NetworkModel / EdgeOrchestrator / TaskGenerator / MobilityModel` | Replace any model with a subclass. Each one is created **by reflection with a fixed constructor signature**, e.g. `(SimLog, PureEdgeSim, int, int, Scenario)` for the manager and `(SimulationManager)` for the network model. |
| `setCustomOutputFolder`, `setCustomSettingsFolder`, `setCustomFilePath` | Point the simulator at other configuration files and another output folder. |
| `protected Simulation.checkFiles()` and `loadScenarios()` | Skip or replace the XML parsing, and choose the scenarios to run. |
| `SimulationParameters` | 59 `public static`, mutable fields that the parsers fill from `simulation_parameters.properties`, `edge_devices.xml`, `edge_datacenters.xml`, `cloud.xml` and `applications.xml`. They can be written in code. |
| `protected` hooks of the default classes | For example `DefaultComputingNode.executionFinished`, `LocationAwareNode.connectWith`, `EnergyAwareNode.setDeath`, `NetworkLink.setEnergyModel`, `DefaultSimulationManager.taskFailed`. |
| `OnSimulationStartListener`, `OnSimulationEndListener` | Lifecycle callbacks for any entity. |
| `MobilityModel.setParameters(Map)` | Per-node mobility options. These were verified in this analysis: `GaussMarkov` reads `alpha`, `std`, `speed_std`; `ReferencePointGroup` reads `group`, `anchor_speed`, `anchor_pause`; `Waypoint` needs `waypoints`; `LevyWalk` reads `min_step`, `max_step`, `shape`. `DefaultMobilityModel` uses the min/max pause and mobility durations. |

## 3. Gaps and how the SDK works around them

| # | What the SDK needs | Gap in the core | Workaround (no core change) |
| --- | --- | --- | --- |
| G1 | Links that fail and recover | Links have no up/down state. `InfrastructureGraph.pathsMap` caches paths forever, and a missing path throws. | `network.LiveNetwork` keeps its own jgrapht graph of `SdkNetworkLink`s and routes over a `MaskSubgraph` that hides links which are down, nodes which are down, and non-relaying intermediate nodes. Paths are cached per topology version. "No route" loses the transfer instead of throwing. |
| G2 | Per-link channel types | Bandwidth, latency and energy are global per link type in `SimulationParameters`. | `SdkNetworkLink extends NetworkLink`: every instance takes its bandwidth (up or down), latency (fixed plus per km of the *current* distance), loss and energy per bit from its `ChannelSpec`. The core `NetworkLinkTypes` (`WAN`, `MAN`, `LAN`, `IGNORE`) is set to the closest value so that the core graph stays meaningful. |
| G3 | Packet loss | None. | Applied per hop when a transfer leaves a link. With `RETRANSMIT`, every retransmission round costs a round trip, and the transfer is lost after `maxRetries` rounds. With `DROP`, any lost packet loses the transfer. |
| G4 | Several links per device | A device has one current up link and one current down link (`getCurrentLink`), re-attached to the closest edge data center every tick. | Device links are ordinary graph edges, so a device can have several. `SdkNode` disables `connectWith`, and the first link of a device is set as its core up/down link so that core code that reads it still works. |
| G5 | Crash-stop and crash-recovery | Nodes only die when their battery is empty. | `SdkNode extends DefaultComputingNode` adds crash holds and an epoch. `isDead()` is true while crashed. A crash empties the queue and frees the cores. A task that finishes on a node that crashed meanwhile is ignored (its epoch is older). Battery death (`setDeath`) becomes a crash-stop. |
| G6 | Partitions, fault events, ground truth | None. | The `fault` package: `FaultInjector` adds and removes per-link **down causes** (`FAULT`, `PARTITION`, `OUT_OF_RANGE`, `ENDPOINT_CRASHED`), counted per cause so that overlapping faults compose. A partition cuts every link between two groups, and heal removes exactly those causes. Every fault goes to the `FaultLog`. |
| G7 | True failure reasons | `Task.FailureReason` is a closed enum. | The SDK keeps its own `TaskOutcome` (`NO_ROUTE`, `LINK_LOST`, `PACKET_LOSS`, `NODE_CRASHED`, `DEVICE_DOWN`, `TIMED_OUT`, …). Algorithms never see it. |
| G8 | A task life cycle where faults matter and decisions are local | `DefaultSimulationManager` routes every task through a global orchestrator and judges mobility failures by distance (`sameLocation`). | `SdkSimulationManager extends SimulationManager` runs the SDK `TaskEngine`. Tasks are placed at the device by the candidate's `OrchestrationPolicy`, sent as transfers, executed on core nodes with `submitTask`, and returned as transfers. The first result wins, and the deadline is checked at the device. The core orchestrator and network model are kept as empty shells (`SdkOrchestrator`, `SdkNetworkModel`). |
| G9 | Passing a run's description to classes created by reflection | Fixed constructors and static configuration. | `engine.RunContext` is a static holder, guarded by a global lock, that the reflected classes read. `ParametersBridge` writes `SimulationParameters`, with parallelism off, no pauses and no charts. **One run at a time per JVM**: experiments run sequentially. |
| G10 | Scenarios in code, without XML | `checkFiles()` parses five files. | `SdkSimulation extends Simulation` overrides `checkFiles()` (the parameters are already set) and `loadScenarios()` (one scenario). Nodes come from `SdkNodesGenerator` and links from `SdkTopologyCreator`. Because `RunMetadata` copies the settings files next to the core output, the bridge writes small provenance files and points the core at them. |
| G11 | Energy per bit per channel | Fixed per connectivity type. | `SdkEnergyModel extends EnergyModelComputingNode` takes network energy in joules from the channel (split between the two endpoints of every hop) and adds an optional constant propulsion drain. |
| G12 | Data and storage | None. | The `storage` package: a versioned key-value store per node (`StorageOps`), client operations drawn from a `StorageWorkload`, and the strategies' messages sent as real transfers. |
| G13 | Comparable metrics and CSVs | `SimLog` writes fixed columns, and comparisons are limited to the core's `orchestrationAlgorithms` strings. | `metrics`: a `Metric` catalogue computed from `RunData`, `CsvExporter`, `ComparisonReport` (mean, CI95, delta against a baseline, best) and `ComparisonCharts` (XChart, already a core dependency). |
| G14 | Errors surfaced to the developer | `SimulationThread` catches every exception and only prints it. | Every SDK and developer callback runs inside `SdkWorld.guard`, which records the first exception and terminates the run. `Runner` then throws `SdkRunException` with the cause. |
| G15 | Identical conditions for compared algorithms | The core has one random stream, so draws made by an algorithm could shift the workload. | Separate seeds per concern. The workload (tasks, storage operations, speeds) comes from its own `Random`s. The core `Randomness` is seeded with the workload seed and is only drawn from while paths are generated at start. Faults come from the fault model's stream, and each candidate has its own stream (also used for packet-loss draws). This was verified: `MobilityModel` draws in its constructor and in `generatePath()` only, both before the SDK starts the algorithms (at time 0, after every `onSimulationStart`). `ExperimentIT` checks that faults and task arrivals are identical across candidates. |

Two further items were verified during the analysis:

- `Orchestrator.findComputingNode` is not called in an SDK run, because placement is the policy's.
- `NetworkLinkTypes` has only `WAN`, `MAN`, `LAN` and `IGNORE`.

## 4. How a run flows

```mermaid
sequenceDiagram
  participant Dev as Developer
  participant E as Experiment
  participant Ctx as RunContext
  participant PB as ParametersBridge
  participant S as SdkSimulation (core)
  participant M as SdkSimulationManager
  participant FI as FaultInjector
  participant Net as LiveNetwork
  participant A as Candidate algorithm
  Dev->>E: candidates × fault models × params × replications
  loop each run (sequential)
    E->>Ctx: open(spec, candidate, seeds)
    E->>PB: apply(spec) → SimulationParameters.*
    E->>S: launchSimulation()
    S->>M: reflection ctor; generators read Ctx
    M->>FI: t=0: faultModel.install(scheduler, faultRng)
    FI->>Net: add/remove down causes, crash/recover nodes
    Note over A,Net: no fault event reaches A
    A->>Net: send / request (may be lost silently)
    Net-->>A: onMessage / onReply, or A's own timeout fires
    M->>Ctx: end: RunData
  end
  E->>Dev: ExperimentResult + CSVs + charts
```

## 5. The information boundary

Fault events are **not** delivered to algorithms. An algorithm sees only what a
deployed node could know:

- the design topology;
- its own node's state;
- the messages it receives;
- the replies to its requests, or its own timeouts;
- task results, and the deadlines of its own tasks;
- the fact that its node restarted.

The `detect` package offers failure detectors (heartbeat, phi accrual) built only
on messages, so detection costs bandwidth and time as it would in a real
system. Declarations (`ctx.declare(...)`) let the SDK score an algorithm's
beliefs against the ground truth without telling it anything. An integration
test (`InformationBoundaryIT`) checks the boundary.

## 6. Limits to keep in mind

- **One run per JVM at a time.** The core's configuration is static. An
  experiment is a sequential loop; it is fast because SDK scenarios are small,
  about 0.1 s per run for the examples.
- **Quantisation.** Transfers advance every `network_update_interval` (0.02 s by
  default) and positions every second. Absolute delays are approximate;
  comparisons under identical seeds are the intended use.
- **Static partition membership.** A partition's groups are resolved when it
  starts. A node that moves does not change group. Range-based link loss is
  modelled separately, as emergent faults.
- **Honest algorithms by convention.** Java cannot prevent a candidate from
  sharing state between nodes through its fields. `NodeLocal`, per-node handlers
  and a narrow `SdkContext` make the honest path the easy one.
