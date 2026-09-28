---
id: B-17
title: "Take kafkakn 0.1.0.13: close the producer within kore's release, and remeasure the stopped broker"
status: done
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

## Findings (2026-09-28)

Everything ran on the Linux box. The first commit of this branch pins kafkakn alone and keeps `close()`: that build
is the control. The second calls `close(releaseGroup − 500 ms)`. `distribution/.../lib` holds
`kafkakn-core-jvm-0.1.0.13.jar`.

- **AC: the pin, both suites.** `make build` green on both commits: `jvmTest` and `linuxX64Test` 28 tests each, no
  failures, the result files from each run.
- **AC: `ci/b-08/run.sh native|jvm 3 pause`, the broker paused, five records in flight at `SIGTERM`:**

  | | `close()` (control) | `close(2.5 s)` |
  |---|---|---|
  | native release | `DEADLINE_EXCEEDED` at 3.000 s, 3 of 3 | `COMPLETED` in 2.501 to 2.511 s, 3 of 3 |
  | native `SIGTERM` to exit | 8 041 to 8 723 ms | 7 534 to 7 609 ms |
  | JVM release | `DEADLINE_EXCEEDED` at 3.003 s, 3 of 3 | `COMPLETED` in 2.508 s, 3 of 3 |
  | JVM `SIGTERM` to exit | 8 342 to 9 005 ms | 7 532 to 8 323 ms |
  | records in the topic afterwards | 5 of 5, every round | 5 of 5, except one JVM round with 2 |

  Every one of those records had been answered `504` before the signal, so every answer was true either way. The
  JVM round with 2 of 5 is kafkakn's amendment in action: the force close failed batches that were in flight, and
  some of them had reached the broker.
- **AC: `ci/b-08/run.sh native|jvm 3`, the broker stopped just before the five publishes:**
  - control: native `429` 3 of 3; the JVM `504` 2 of 3, `429` 1 of 3;
  - with `close(timeout)`: native `429` 3 of 3; the JVM `429` 2 of 3, `504` 1 of 3. A JVM round with `504` now ends
    its release in 2.51 s with `COMPLETED`, where the control's were cut at 3 s.

  None of the records was in the topic afterwards. Under kafkakn `0.1.0.11`, native answered `504` 5 of 5 (B-08).
  So native now refuses (kafkakn B-80), and the JVM answers either way at the instant of the stop, which kafkakn's
  contract now says it does not promise. The feature's quirk, the research and the README say this instead of
  "the builds disagree".
  - The native `stop` rounds of the `close(timeout)` build printed nothing in the first combined run: the script's
    output went through `grep round`, which would also have hidden a broker that did not come up. Run again on its
    own, the same build gave the three rounds above.
- **AC: `ci/b-09/run.sh`, 5 rounds per build, with `close(timeout)`: 0 of 5 rounds with a disagreement on either
  build.** 10 367 to 18 341 `200`s a round, 0 to 8 resets a round.
- **The documents.** The `504` row of the endpoint and research H3 now cite kafkakn's contract: a failed `await()`
  is possibly written for a record in flight. No status changed: that was already `504` (B-06).
