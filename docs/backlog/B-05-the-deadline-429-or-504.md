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

- **The decision and its reason.** `send` runs under `PUBLISH_DEADLINE_MS`, enforced by mostik and not by
  producer keys, because no library bound is portable (research §1.4). When the deadline expires:
  - if kafkakn says the record was never queued: `429 not-queued` with `Retry-After`;
  - otherwise: `504 outcome-unknown`, with a body carrying `"outcome": "unknown"` and `"retrySafe": false`.

  A `send` that throws something mostik cannot classify is also `504` (research D2).
- If B-04 leaves one arm without the distinction, that build answers `504` for every expiry, and the
  feature document says so per build (research open question 3).
- Not covered: whether a thrown `send` can be `502` (B-06).

- AC, *queue full*: with the producer's queue held at its bound past the deadline, the client gets `429`,
  and after the queue drains the record is **not** in the topic. Checked by key with B-02's reader.
- AC, *queued, broker silent*: with the broker paused after the record is queued, the client gets `504`
  within the deadline plus a margin. After the broker resumes, the record **is** found in the topic.
  This scenario is what proves the word "unknown" is needed.
- AC: both builds give the same status in both scenarios, or the documents name the difference.
- Anchors: the route's feature package in `server/src/commonMain/kotlin/`.
