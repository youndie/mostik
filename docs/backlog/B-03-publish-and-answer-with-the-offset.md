---
id: B-03
title: "POST /topics/{topic}/records answers 200 with the offset the broker gave"
status: done
priority: P1
size: M
stage: stage-2-publish
epic: feature-publish-over-http
blocked_by: [B-01, B-02]
---

# B-03 — `POST /topics/{topic}/records` answers `200` with the offset the broker gave

The happy path, and the two refusals that happen before the producer is touched. Feature: [feature-publish-over-http](../features/feature-publish-over-http.md).

- **The decision and its reason.** The value is the request body, byte for byte. The key comes from
  `Record-Key` and record headers from `Record-Header-<name>`, in order (research D1). `200` is sent
  only after `send` has returned `RecordMetadata`, and carries `topic`, `partition`, `offset` and
  `timestamp`. A topic outside `TOPICS` is `404 topic-not-found`, and a body over `MAX_RECORD_BYTES` is
  `413 record-too-large`. Neither reaches `send`.
- The error body is `{"error": "<code>", "detail": "<sentence>"}`. The code is contract and the sentence
  is not.
- Not covered: the deadline. This item's `send` has no bound, which is B-05's work.

- AC: on both builds, posting the sample order to `orders` answers `200`, and B-02's reader prints the
  same bytes at the returned `(partition, offset)`, with the key and the header.
- AC: a post to `audit` answers `404` and an oversized body `413`, on both builds. For each, the
  producer's `metrics()` shows no record sent.
- AC: the drafted feature and endpoint documents on **docs/drafts** get their code anchors from this
  item, and the scenarios it proves go `active`.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/mostik/publish/PublishRoutes.kt`, `ci/b-03/run.sh`.

## Findings (2026-09-27)

Where each check ran: the build, both suites and the end-to-end run on the Linux box; the documentation gate
on the Mac. There is no CI.

- **AC: the sample order answers `200`, and the reader prints the same bytes at the returned place, with the
  key and the header, on both builds.** `ci/b-03/run.sh native` and `ci/b-03/run.sh jvm`, both `PASS`:

  ```
  [native] 200 {"topic":"orders","partition":1,"offset":1,"timestamp":1790465612467}
  [native]     the reader at 1/1: Partition:1 | Offset:1 | trace-id:7f3a9c | order-1042-… | {"orderId":1042,"amount":"19.90","currency":"EUR"}
  [jvm] 200 {"topic":"orders","partition":2,"offset":0,"timestamp":1790465634211}
  [jvm]     the reader at 2/0: Partition:2 | Offset:0 | trace-id:7f3a9c | order-1042-… | {"orderId":1042,"amount":"19.90","currency":"EUR"}
  ```

- **AC: `audit` is `404` and an oversized body `413` on both builds, and neither reaches the producer.** The
  item asked for evidence from the producer's `metrics()`. mostik does not expose those metrics, so the evidence
  is two other things. First, `PublishRoutesTest` asserts that the send function was never called, on `jvm` and
  `linuxX64`. Second, end to end, the reader finds no record with the oversized record's key, and no topic
  called `audit` exists afterwards. That is a substitution, stated rather than hidden.
- **AC: the drafted documents got their code anchors and went `active`.** `docs/drafts` merged into this
  branch. Every document was re-read against the code, not flipped, and three sentences changed:
  - record headers are **not** in arrival order: sent as `Zeta:1, alpha:2, Zeta:3`, they reach the topic as
    `Zeta:1, Zeta:3, alpha:2`, grouped by name, on both builds. Ktor's request headers are a map from name to
    values. Research D1 and the endpoint document said "in order", and both are corrected;
  - the deadline flow, `429`, `MOSTIK_QUEUE_WAIT_MS` and the `KAFKA_MAX_BLOCK_MS` refusal are marked *target*
    (B-05), because `send` is unbounded today;
  - the AOT cache stays trained on `/version`. A publishing workload needs a broker at training time, and
    none is there.
- **Suites:** 19 tests on `jvm` and 19 on `linuxX64`, none failed, from fresh result files (the directory was
  deleted first).
- **Mutants, each killed by the test named:**
  - allowlist removed: `a topic outside the allowlist is 404 and nothing is sent`;
  - limit `<` instead of `<=`: `a body exactly at the limit is sent`;
  - key dropped: `a record is sent as the body key and headers and the answer is where it landed`.
- **A decision this item took:** a `send` that throws answers `504 outcome-unknown` now, rather than
  escaping as a `500`. That is research D2's rule for an unclassified failure. B-05 adds the deadline, and B-06
  decides whether any throw can be `502`.
- **Found on the way:** Ktor 3.6 deprecates `ByteReadChannel.readRemaining(Long)` and then
  `readBuffer(Int)`, and with `-Werror` each is a compile error. The route uses `readBuffer(Long)`.
