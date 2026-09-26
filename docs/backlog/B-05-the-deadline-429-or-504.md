---
id: B-05
title: "The publish deadline: 429 when provably never queued, 504 outcome-unknown otherwise"
status: open
priority: P0
size: M
stage: stage-3-bounded-wait
blocked_by: [B-03, B-04]
---

# B-05 — the publish deadline: `429` when provably never queued, `504 outcome-unknown` otherwise

The reason mostik exists. Feature: publish over HTTP.

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
- Anchors: the route's feature package in `server/src/commonMain/kotlin/`.
