---
id: B-05
title: "The publish deadline: 429 when provably never queued, 504 outcome-unknown otherwise"
status: done
priority: P0
size: M
stage: stage-3-bounded-wait
epic: feature-publish-over-http
blocked_by: [B-03, B-04]
---

# B-05 — the publish deadline: `429` when provably never queued, `504 outcome-unknown` otherwise

The reason mostik exists. Feature: [feature-publish-over-http](../features/feature-publish-over-http.md).

- **The decision and its reason.** The deadline is split in two, following kafkakn B-74 (research §1.4,
  correction):
  1. `enqueue` runs with no coroutine timeout, bounded by `max.block.ms`, which mostik sets from
     `MOSTIK_QUEUE_WAIT_MS`. A `RecordNotQueuedException` is `429 not-queued` with `Retry-After`.
  2. `Delivery.await()` runs under `withTimeout(PUBLISH_DEADLINE_MS − time spent in step 1)`. An expiry is
     `504 outcome-unknown`, with a body carrying `"outcome": "unknown"` and `"retrySafe": false`.

  Anything else thrown in step 2 that mostik cannot classify is also `504` (research D2).
- **Rejected: a timeout around `enqueue`.** kafkakn B-73 measured that on the JVM a caller cut while the
  client waits for room gets control back only when the client lets go, about 5 s later in its run. The
  record may be queued by then. That timeout would break the deadline and would answer `429` for a record
  that lands.
- The start-up refuses `MOSTIK_QUEUE_WAIT_MS ≥ MOSTIK_PUBLISH_DEADLINE_MS`, and refuses any
  `KAFKA_MAX_BLOCK_MS`: one value with two sources drifts.
- Not covered: whether a thrown `send` can be `502` (B-06).

- AC, *queue full*: with the producer's queue held at its bound past the deadline, the client gets `429`,
  and after the queue drains the record is **not** in the topic. Checked by key with B-02's reader.
- AC, *queued, broker silent*: with the broker paused after the record is queued, the client gets `504`
  within the deadline plus a margin. After the broker resumes, the record **is** found in the topic.
  This scenario is what proves the word "unknown" is needed.
- AC: both builds give the same status in both scenarios; kafkakn measured the same split on both arms.
- AC: a queue wait of 5 000 ms with a deadline of 5 000 ms, or any `KAFKA_MAX_BLOCK_MS`, stops the
  start-up, and the message names the keys.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/mostik/publish/PublishRoutes.kt`, `ci/b-05/run.sh`.

## Findings (2026-09-27)

Where each check ran: the build, both suites and the end-to-end runs on the Linux box; the documentation gate on
the Mac. There is no CI.

- **AC, queue full, and AC, queued with the broker silent, on both builds.** `ci/b-05/run.sh native` and
  `ci/b-05/run.sh jvm`, both `PASS`, with a 3 000 ms deadline and a 1 000 ms queue wait:

  | | native | JVM |
  |---|---|---|
  | broker silent: status, time | `504`, 3 011 ms | `504`, 3 022 ms |
  | …record after `resume` | **in the topic** | **in the topic** |
  | queue full: status, time | `429`, 1 013 ms | `429`, 1 021 ms |
  | …record after the queue drained | **not in the topic** | **not in the topic** |
  | 60 requests filling the queue | 10 × `504`, 50 × `429` | 10 × `504`, 50 × `429` |

  The queue's bound is a platform key: `KAFKA_QUEUE_BUFFERING_MAX_MESSAGES=10` on native and
  `KAFKA_BUFFER_MEMORY=32768` on the JVM, with 1 KiB fillers. Each build refuses the other's key. That the two
  builds agree down to the fillers' split is a coincidence of those two numbers, not a promise.
- **AC: same status on both builds.** Held in every observation above.
- **AC: the start-up refusals, on both builds, with the keys named.**
  *"MOSTIK_QUEUE_WAIT_MS (5000) must be shorter than MOSTIK_PUBLISH_DEADLINE_MS (5000): nothing would be left of
  the deadline for the broker's answer"*, and *"KAFKA_MAX_BLOCK_MS is not read: max.block.ms comes from
  MOSTIK_QUEUE_WAIT_MS"*. Each exits 1.
- **Suites:** 26 tests on `jvm` and 26 on `linuxX64`, none failed, from fresh result files. The route tests
  cover `429` with `Retry-After`, `502`, a delivery that never answers, a failing delivery, and that step 2 gets
  what is *left* of the deadline, using a `TestTimeSource` that makes step 1 take 900 ms of 1 000.
- **Mutants, each killed by the test named:**
  - the full deadline in step 2: `the time spent queueing is taken off the deadline`;
  - no `429` branch: `a record not queued is 429 with Retry-After`;
  - `max.block.ms` not owned: `KAFKA_MAX_BLOCK_MS is refused and names MOSTIK_QUEUE_WAIT_MS`.
- **Decisions this item took, recorded where they are read:**
  - any failure of `enqueue` other than `RecordNotQueuedException` is `502 producer-refused`, not `429`: the
    record was not queued, but waiting will not help (endpoint-records);
  - `Retry-After` is the queue wait rounded up to whole seconds, at least 1 (research, open question 2).
- **Found on the way:** two route tests first failed with `406 Not Acceptable`. They mounted the route without
  `ContentNegotiation`, so Ktor had no way to render the body. The test helper now installs the same JSON as
  `mostikModule`. That is a test-harness defect, not a route one.
