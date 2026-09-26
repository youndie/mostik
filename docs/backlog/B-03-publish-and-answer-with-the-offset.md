---
id: B-03
title: "POST /topics/{topic}/records answers 200 with the offset the broker gave"
status: open
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
- Anchors: `server/src/commonMain/kotlin/` (the route's feature package, new).
