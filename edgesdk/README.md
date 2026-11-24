# PureEdgeSim Edge SDK

A developer-facing layer on top of PureEdgeSim for implementing and testing
**algorithms, storage strategies and whole systems across the edge-to-cloud
continuum, with a focus on fault tolerance under network partitions**.

The SDK only *extends* PureEdgeSim, through subclasses, the `setCustom*` plugs and
`SimulationParameters`. It does not modify the core. See
[docs/architecture-analysis.md](../docs/architecture-analysis.md) for how.

- **Fluent builder** with presets for UAVs, UGVs, sensors, phones, edge
  servers, trucks, clouds, and channels (LoRa, RF, Wi-Fi, 4G, 5G, Ethernet,
  fibre).
- **Class-based extension.** Subclass `OrchestrationPolicy` (offloading),
  `StorageStrategy` (replication and caching) or `FaultModel`.
- **Faults.** Links, nodes, clouds and groups can be hit. Faults can be
  stochastic (MTBF/MTTR, per-interval probability), scripted (Java or a text
  format), or emergent (out of radio range). The types are crash-stop,
  crash-recovery, link down, degraded link and partition.
- **Algorithms discover faults themselves.** They get no fault callbacks: only
  messages, replies, their own timeouts and local state. Failure detectors
  (heartbeat, phi accrual) are provided as building blocks.
- **Experiments.** You can run one algorithm, or several under *identical*
  workload, mobility and faults (common random numbers), with replications and
  parameter sweeps. The results are CSV files, a comparison against a baseline
  with 95 % confidence intervals, and PNG charts.

## Quick start

Requirements: JDK 17+ and Maven.

```bash
# 1. Install PureEdgeSim (the core) into your local Maven repository
mvn install -DskipTests

# 2. Build and test the SDK
mvn -f edgesdk/pom.xml install          # unit + integration tests (~10 s)
mvn -f edgesdk/pom.xml install -P unit-tests   # unit tests only

# 3. Run an example
mvn -f edgesdk/pom.xml -pl edgesdk-examples exec:java \
    -Dexec.mainClass=zip.hedlund.pureedgesim.edgesdk.examples.Story8CompareAlgorithms
```

To use the SDK in your own project, depend on `zip.hedlund:edgesdk-core:0.1.0-SNAPSHOT`.

### 1. Describe a system

```java
Scenario scenario = Scenario.builder("uav-swarm")
    .duration(600).seed(42).map(3_000, 3_000)
    .addEdgeNode("truck", EdgePresets.TRUCK_SERVER.withMobility(MobilitySpec.group("convoy", 8)), Layout.at(1_500, 1_500))
    .addEndDevices(DevicePresets.UAV.withMobility(MobilitySpec.group("convoy", 8)), 12, Layout.near("truck", 60))
    .addCloud("cloud", CloudPresets.REGIONAL_DC, Layout.at(0, 3_000))
    .connect(Select.tier(Tier.END_DEVICE), Select.name("truck"), ChannelPresets.WIFI)
    .connect("truck", "cloud", ChannelPresets.CELLULAR_5G.withRangeM(Double.POSITIVE_INFINITY))
    .group("swarm", Select.names("truck", "uav-*"))
    .withWorkload(WorkloadPresets.VIDEO_ANALYTICS)
    .withFaults(FaultScript.parse("""
        120s partition swarm | cloud for 60s
        300s crash uav-3 recover 30s
        """))
    .build();          // validates: unknown nodes, unsupported channels, unconnected nodes, empty fault targets
```

### 2. Write an algorithm

```java
public class MyOffloading extends OrchestrationPolicy {
    @Override public Placement place(NodeRef device, TaskRequest task) {
        return ctx.topology().nearest(device, ctx.topology().edgeNodes())
                  .map(edge -> Placement.on(edge).withRetryAfter(1.0))  // a local timer, not a fault signal
                  .orElse(Placement.local());
    }
    @Override public Placement onAttemptTimedOut(NodeRef device, TaskRequest task, NodeRef tried, int attempt) {
        return Placement.local();                                       // no result in time: run it here
    }
}
```

An algorithm sees the design topology, its node's own state, its messages
(`ctx.send`, `ctx.request` with a timeout), its timers and its task results.
It never sees link state, partitions or crashes of other nodes. Storage
strategies extend `StorageStrategy` in the same way (`onWrite`, `onRead`, and
`onRequest`/`onMessage` for replica traffic).

### 3. Run one, or compare several

```java
// one algorithm
RunResult r = scenario.run(Candidate.of("mine", MyOffloading::new), Path.of("output/mine"));
System.out.println(r.value(Metrics.SUCCESS_DURING_PARTITION));

// several, under identical conditions
ExperimentResult result = Experiment.on(scenario)
    .candidate(Candidate.of("cloud-only", CloudOnly::new))
    .candidate(Candidate.of("heartbeat", HeartbeatFallback::new).storage(EventualGossip::new))
    .candidate(Candidate.of("mine", MyOffloading::new).storage(() -> new Quorum(3, 2, 2)))
    .faultModels(NoFaults.INSTANCE, scenario.faultModel(), StochasticFaultModel.links(300, 20))
    .vary("uavs", List.of(6, 12), (b, n) -> b.addEndDevices(DevicePresets.UAV, n, Layout.near("truck", 60)))
    .replications(5).baseline("cloud-only")
    .metrics(Metrics.TASK_SUCCESS, Metrics.SUCCESS_DURING_PARTITION, Metrics.TIME_TO_RECOVER,
             Metrics.STORAGE_AVAILABILITY, Metrics.CONTROL_OVERHEAD)
    .run(Path.of("output/compare"));
```

The console prints a table per condition (mean ± CI95, Δ against the baseline,
`*` for the best). The output folder holds:

| File | Content |
| --- | --- |
| `runs.csv` | one row per run: candidate, condition, parameters, seeds, every metric, custom counters |
| `comparison.csv` / `comparison_wide.csv` | per condition × candidate × metric: n, mean, std, CI95, Δ and Δ % against the baseline, best |
| `tasks.csv` | every task with its true outcome (`SUCCESS`, `NO_ROUTE`, `LINK_LOST`, `NODE_CRASHED`, …) |
| `faults.csv` | the ground-truth fault log (identical across candidates within a replication) |
| `timeline.csv` | links and nodes down, partition, windowed success, divergent keys, control bytes |
| `storage.csv` | every read and write: success, versions behind, staleness |
| `charts/` | a bar chart per metric (with CI) and the success-over-time timeline |

## Package map

| Package | Content |
| --- | --- |
| `api` | `Scenario`, `ScenarioBuilder`, `Select`, `Layout`, `Candidate`, `Experiment`, `ExperimentResult`, `RunResult` |
| `model` | `NodeSpec`, `ChannelSpec`, `ComputeSpec`, `MemorySpec`, `EnergySpec`, `MobilitySpec`, `WorkloadSpec`, `LinkSpec`, `DesignTopology` |
| `presets` | `DevicePresets`, `EdgePresets`, `CloudPresets`, `ChannelPresets`, `WorkloadPresets` (values: [docs/edgesdk-presets.md](../docs/edgesdk-presets.md)) |
| `fault` | `FaultModel`, `FaultScheduler`, `Targets`, `StochasticFaultModel`, `FaultScript`/`ScriptedFaultModel`, `CompositeFaultModel`, `NoFaults`, `FaultLog` |
| `algorithm` | `Algorithm`, `SdkContext`, `OrchestrationPolicy`, `Placement`, `NodeLocal`, `Deferred`, `Declaration`; `policies/` (with `policies/flocky`: the Flocky orchestrator) |
| `detect` | `HeartbeatDetector`, `PhiAccrualDetector`, `TimeoutTracker`, `MembershipView` |
| `storage` | `StorageStrategy`, `StorageOps`, `StorageWorkload`, `VersionedValue`, `VectorClock`; `strategies/` |
| `metrics` | `Metric`, `Metrics` (catalogue), `RunData`, `ComparisonReport`, `CsvExporter`, `ComparisonCharts` |
| `network`, `engine` | internals: live topology, transfers, the PureEdgeSim plug-ins |

## User stories and examples

See [docs/user-stories.md](../docs/user-stories.md): nine stories, each with
acceptance criteria and a runnable example in `edgesdk-examples`.

## Things to know

- One simulation runs per JVM at a time (PureEdgeSim's configuration is
  static), so experiments are sequential. Small scenarios take about 0.1 s per run.
- Transfers are advanced every 0.02 s and positions every second. Compare
  candidates under identical seeds rather than trusting absolute delays.
- A partition's groups are fixed when it starts. Nodes that move lose links by
  range (emergent faults), not by changing group.
