---
description: "Developer user stories for the PureEdgeSim Edge SDK, with acceptance criteria and runnable examples."
---
# Edge SDK user stories

Each story has a runnable example in
`edgesdk/edgesdk-examples/src/main/java/zip/hedlund/pureedgesim/edgesdk/examples/`.
Run one with:

```
mvn install -DskipTests                                   # once: PureEdgeSim into the local repository
mvn -f edgesdk/pom.xml install -DskipTests                # once: the SDK
mvn -f edgesdk/pom.xml -pl edgesdk-examples exec:java \
    -Dexec.mainClass=zip.hedlund.pureedgesim.edgesdk.examples.Story1CustomOffloading
```

Results go to `output/edgesdk/<story>/` (change the folder with
`-Dedgesdk.output=...`). `ExamplesIT` runs every example in a shortened form.

---

## 1. Custom task-offloading algorithm

**As a developer, I want to** implement my own task-offloading algorithm
(device → edge → cloud) by subclassing a single class, **so that** I can
evaluate it under faults without touching the simulator.

Acceptance criteria:

- [x] Subclassing `OrchestrationPolicy` and implementing `place(device, task)` is enough; retries (`onAttemptTimedOut`), results (`onTaskCompleted`) and deadline misses (`onTaskTimedOut`) are optional hooks.
- [x] Decisions are made at the device with local knowledge: the design topology, the device's own state, and what the algorithm learned from its own results and messages. The algorithm is never told about faults.
- [x] Per-node state is kept in `NodeLocal`, which a crash wipes.
- [x] The algorithm can publish custom metrics (`ctx.metrics()`) that are reported and comparable.
- [x] It can be compared with a built-in baseline in one call.

Example: [`Story1CustomOffloading`](../edgesdk/edgesdk-examples/src/main/java/zip/hedlund/pureedgesim/edgesdk/examples/Story1CustomOffloading.java).
`LatencyLearner` learns server response times from its own results, backs off
from servers whose tasks timed out, and falls back to local execution. It is
compared with `NearestByDesign` while an edge server crashes and recovers.

## 2. Distributed storage strategy tested under partition

**As a developer, I want to** implement a replication strategy (edge caching,
eventual consistency) and test it under a network partition, **so that** I can
measure its availability and consistency before and after the partition heals.

Acceptance criteria:

- [x] Subclassing `StorageStrategy` (`onWrite`, `onRead`, plus message handlers) is enough; data moves only through messages that cost bandwidth and can be lost.
- [x] Reads and writes come from a `StorageWorkload` (Zipf keys), identical for every strategy compared.
- [x] Requests can be answered later (`Deferred`), e.g. after the backups acknowledge.
- [x] The SDK reports availability (overall and during the partition), stale reads, staleness, divergence between replicas, and time to converge after heal.
- [x] Built-in strategies to compare against: `PrimaryBackup`, `Quorum(n, r, w)`, `EventualGossip`, `EdgeCache`.

Example: [`Story2ReplicationUnderPartition`](../edgesdk/edgesdk-examples/src/main/java/zip/hedlund/pureedgesim/edgesdk/examples/Story2ReplicationUnderPartition.java).
`PushWithRetry` (write to the nearest edge, push to the others, retry from an
outbox) against `Quorum(3,2,2)` and `EventualGossip`, while the west region is
partitioned for 90 s.

## 3. A whole system from presets

**As a developer, I want to** build a whole system (a UAV swarm with a
truck-mounted edge server and cloud backhaul) with the fluent builder and
presets only, **so that** I get a realistic scenario in a few lines.

Acceptance criteria:

- [x] Devices, edge nodes (static and mobile) and several clouds come from presets; each edge node can link to one or more clouds.
- [x] Links are declared per channel; a node can have several links over different channels.
- [x] Group mobility keeps the swarm around its truck.
- [x] Stochastic and scripted faults compose (`model.and(other)`).
- [x] `build()` validates the scenario: unknown nodes, unsupported channels, unconnected nodes and fault targets that do not exist fail with a clear message.

Example: [`Story3UavSwarmFromPresets`](../edgesdk/edgesdk-examples/src/main/java/zip/hedlund/pureedgesim/edgesdk/examples/Story3UavSwarmFromPresets.java).

## 4. Custom device and channel presets

**As a developer, I want to** define my own device or channel preset by
overriding defaults (or from scratch), **so that** I can model hardware the
catalogue does not have.

Acceptance criteria:

- [x] Presets are immutable records; `with…` methods return modified copies and never change the original.
- [x] A preset can be written from scratch with the record constructors.
- [x] Custom channels can be added to existing presets (`withChannel`) and used to link nodes.
- [x] Invalid values (negative bandwidth, loss ≥ 1, zero cores, a static edge node that moves) are rejected.

Example: [`Story4CustomPresets`](../edgesdk/edgesdk-examples/src/main/java/zip/hedlund/pureedgesim/edgesdk/examples/Story4CustomPresets.java).
It defines an agricultural drone with propulsion drain, a 3 km mesh radio, a
battery-powered field gateway and a satellite backhaul.

## 5. The same algorithm under several fault models

**As a developer, I want to** run the same algorithm under no faults, random
faults and a scripted partition, **so that** I can see how each kind of fault
affects it.

Acceptance criteria:

- [x] `Experiment.faultModels(...)` runs every candidate under each model, with the same workload for every model in a replication.
- [x] Stochastic faults support MTBF/MTTR distributions per link or node, per-interval failure probabilities, random degradations, random crash-recovery and random partitions.
- [x] `comparison.csv` and the console table show one block per fault model.

Example: [`Story5CompareFaultModels`](../edgesdk/edgesdk-examples/src/main/java/zip/hedlund/pureedgesim/edgesdk/examples/Story5CompareFaultModels.java).

## 6. Scripted partition and heal

**As a developer, I want to** script a partition ("at t=120 s, cut district 1
from the cloud for 90 s") and check how my algorithm behaves during and after
healing, **so that** I can verify its recovery.

Acceptance criteria:

- [x] Scripts can be written in Java (`FaultScript.builder()`) or in a text format (`FaultScript.parse` / `load`), with partitions, crashes (stop or recover), link failures and degradations.
- [x] A partition cuts exactly the links between its groups and heal restores exactly those, also when other faults overlap.
- [x] The run reports task outcomes split by time (`tasks.csv`: `during_partition`), the time to recover, the success rate over time (`timeline.csv`), and detection latency when the algorithm declares its suspicions.

Example: [`Story6ScriptedPartitionAndHeal`](../edgesdk/edgesdk-examples/src/main/java/zip/hedlund/pureedgesim/edgesdk/examples/Story6ScriptedPartitionAndHeal.java).

## 7. Parameter sweeps in batch

**As a developer, I want to** sweep parameters such as the device count and the
fault rate in batch runs, **so that** I can study how my algorithm scales and
degrades.

Acceptance criteria:

- [x] `Experiment.vary(name, values, (builder, value) -> …)` derives one scenario per value; several dimensions form a cartesian product.
- [x] Every combination runs for every candidate and replication; parameters appear as `param.<name>` columns in every CSV.
- [x] Results carry mean, standard deviation and 95 % confidence intervals.

Example: [`Story7ParameterSweep`](../edgesdk/edgesdk-examples/src/main/java/zip/hedlund/pureedgesim/edgesdk/examples/Story7ParameterSweep.java).

## 8. Compare several algorithms under identical faults

**As a developer, I want to** define several algorithms and have the SDK either
run one of them or all of them under exactly the same conditions, **so that**
differences in the metrics come from the algorithms and not from luck.

Acceptance criteria:

- [x] A `Candidate` names an offloading policy and, optionally, a storage strategy; a fresh instance is created for every run.
- [x] Common random numbers: within a condition and replication, every candidate faces the same tasks, storage operations, mobility and fault realisation (`faults.csv` is identical across candidates).
- [x] One baseline; `comparison.csv` holds mean, CI95, delta and delta % against it, and the best candidate per metric; `comparison_wide.csv` and PNG charts for reading.
- [x] Developers choose the compared metrics from the built-in catalogue or define their own (`Metric.of`).
- [x] `scenario.run(candidate, out)` runs a single candidate with the same outputs.

Example: [`Story8CompareAlgorithms`](../edgesdk/edgesdk-examples/src/main/java/zip/hedlund/pureedgesim/edgesdk/examples/Story8CompareAlgorithms.java).
Four candidates (cloud-only, local-first, nearest, heartbeat fallback), each
with a storage strategy, face a partition and a micro-DC crash over 5
replications.

## 9. Reproduce a published algorithm: MVTORA

**As a researcher, I want to** run a published offloading algorithm and its
baselines in the SDK, **so that** I can compare my own algorithm against it
under faults and mobility the paper did not model.

Acceptance criteria:

- [x] `Mvtora` implements MVTORA (Sun et al., arXiv:2309.16709): the utility of local, MEC-assisted and VFC-assisted computing (Eq. 21), MEC allocation by bisection (Algorithm 2), vehicle selection (Algorithm 3), task division by a genetic algorithm (Algorithm 4), and the decision of Algorithm 5 with a better-response step of the offloading game (Algorithm 1) for each task.
- [x] VFC-assisted offloading divides a task among several vehicles with `Placement.split`.
- [x] The E-UAV learns vehicle states and offloading decisions through messages every slot; devices that stop hearing from it compute locally.
- [x] Restricting the modes gives the paper's ELC, EMC, VTO and MTO baselines.

Example: [`Story9MvtoraPostDisasterRescue`](../edgesdk/edgesdk-examples/src/main/java/zip/hedlund/pureedgesim/edgesdk/examples/Story9MvtoraPostDisasterRescue.java).
Fifteen client UAVs, twenty rescue vehicles and an edge UAV; MVTORA is compared
with ELC, EMC, VTO and MTO on the predicted utility, task success, latency and
device energy.
