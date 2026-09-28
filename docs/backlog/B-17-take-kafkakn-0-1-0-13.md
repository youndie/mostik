---
id: B-17
title: "Take kafkakn 0.1.0.13: close the producer within kore's release, and remeasure the stopped broker"
status: wip
priority: P2
size: S
stage: stage-4-shutdown
epic: feature-shutdown-without-loss
---

# B-17 — take kafkakn 0.1.0.13

mostik pins kafkakn `0.1.0.11`. Three things since then change what mostik can say or do. Feature:
[feature-shutdown-without-loss](../features/feature-shutdown-without-loss.md).

| kafkakn | change | kafkakn item |
|---|---|---|
| `0.1.0.13` (`a0eef18`, #116) | `KafkaProducer.close(timeout)`: records unacknowledged when it runs out fail their `await()` with `ClosedBeforeAcknowledgedException`; `close()` is unchanged | B-91 |
| contract, `a19c2e6` (#108) | a failed `await()` means *not written* for a record never sent and *possibly written* for one in flight. With the broker paused, all five timed-out records were in the topic afterwards | B-83 |
| `367f41e` (#103) | with every broker down, native forgets the topics it described, so its next `enqueue` waits `max.block.ms` and refuses, as the JVM arm does | B-80 |

- **The decision and its reason.**
  - **Close with a timeout inside kore's release.** Today `producer.close()` runs until kore cancels it at
    `releaseGroup` (3 s). On native with records still in the producer that is `RELEASE_POOLS DEADLINE_EXCEEDED`
    (B-08). `close(releaseGroup − 500 ms)` ends on its own, and every record it gives up on fails its `await()` by
    name. kafkakn measured `close(3 s)` at 3 002 to 3 010 ms, so half a second is a wide margin.
  - **No status changes.** Every `await()` failure has been `504` since B-06, which is what B-83's amendment says a
    failure after queueing can mean. The documents gain the contract's own sentence as the reason.
  - **The stopped-broker difference of B-08 may be gone.** B-08 found the JVM build answering `429` and native `504`
    with the broker stopped. B-80 is kafkakn making native refuse there too. This item measures it again rather
    than assuming it.
- Not covered: the drain and the deadline, which do not change.

- AC: `gradle/libs.versions.toml` pins kafkakn `0.1.0.13`. Both suites pass on the Linux box and in CI.
- AC: `ci/b-08/run.sh native|jvm 3 pause`, first with `close()` and then with `close(timeout)`: the release on
  native goes from being cut by kore to ending by itself, and each build still exits within its grace period.
- AC: `ci/b-08/run.sh native|jvm 3` (broker stopped): the statuses per build, recorded. If both builds now answer
  `429`, the quirk comes out of the feature, the research and the README.
- AC: `ci/b-09/run.sh`, 5 rounds per build: zero disagreements.
- Anchors: `gradle/libs.versions.toml`, `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt`,
  `ci/b-08/run.sh`.
