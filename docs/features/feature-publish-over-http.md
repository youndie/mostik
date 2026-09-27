---
id: feature-publish-over-http
title: Publish a record over HTTP, and report its real outcome
type: feature
status: active
owner: unassigned
involved_services:
  - mostik-server
client_entries: []
api:
  - endpoint-records
tags: [publish, deadline]
---

# Publish a record over HTTP, and report its real outcome

## 1. Overview

A `POST` puts one record on an allowlisted topic. The response waits for the broker's acknowledgement
or for the deadline, whichever comes first, and its status tells the caller what happened to the
**record**, not what happened to the request. A caller that gets `200` knows the offset; a caller that
gets `504` knows that nobody knows yet, and that retrying may write the record twice.

`client_entries: []` is the answer "mostik has no client".

## 2. Business rules

* `200` is sent only after `Delivery.await()` returned the broker's metadata, never after the record was
  merely queued.
* A record answered `429`, `502 producer-refused` or `503` is never in the topic.
* A record answered `504` may or may not be in the topic, and the body says `"retrySafe": false`.
* Every request ends within `MOSTIK_PUBLISH_DEADLINE_MS` plus a small margin; none waits out a producer
  timeout (research §1.4). Measured: 3 011 ms (native) and 3 022 ms (JVM) against a 3 000 ms deadline with the
  broker silent (B-05).
* A topic outside `MOSTIK_TOPICS` and a body over `MOSTIK_MAX_RECORD_BYTES` are refused before the
  producer is called.
* No request answers `500` (research D2).

## 3. Flow

1. The route checks the topic against the allowlist and the body against the size limit.
2. It builds a `ProducerRecord` from the body, `Record-Key` and `Record-Header-*` (research D1).
3. `producer.enqueue(record)`, bounded by `max.block.ms` (= `MOSTIK_QUEUE_WAIT_MS`) and **not** by a
   timeout. `RecordNotQueuedException` becomes `429` with `Retry-After`. Any other failure here is
   `502 producer-refused`: the record was not queued, and waiting will not change that.
4. `withTimeout(deadline − time spent in step 3) { delivery.await() }`. Metadata becomes `200`. An expiry, or
   any failure after queueing, becomes `504 outcome-unknown`. Whether a named refusal could be `502` instead is
   B-06.

## 4. Code anchors

| Service | Code |
|---|---|
| mostik-server | `server/src/commonMain/kotlin/io/github/youndie/mostik/publish/` — the route and the outcome mapping |
| mostik-server | `server/src/commonMain/kotlin/io/github/youndie/mostik/MostikConfig.kt` — allowlist, deadline, size limit |
| scenarios' oracle | `ci/broker/` — reads the topic; neither mostik nor kafkakn is asked (B-02) |

## 5. Scenarios (BDD / test cases)

Each runs on both builds, and each is decided by reading the topic, not by asking mostik. The ones not
built yet are marked *target* with the item that builds them. `ci/b-03/run.sh native|jvm` is the end-to-end
run: it starts a build against `ci/broker/` and reads every verdict out of the topic. It is run by hand;
there is no CI.
Sample data: the topic `orders` (3 partitions), key `order-1042`, value
`{"orderId":1042,"amount":"19.90","currency":"EUR"}`, header `trace-id: 7f3a9c`, deadline 5000 ms.

### Scenario: acknowledged
* **Given:** `orders` is in `MOSTIK_TOPICS` and the broker is up
* **When:** a client posts the sample value to `/topics/orders/records` with `Record-Key: order-1042`
  and `Record-Header-trace-id: 7f3a9c`
* **Then:** `200` with `topic`, `partition`, `offset`, `timestamp`
* **And:** the topic holds exactly those bytes, that key and that header at that partition and offset
* **Automated:** `PublishRoutesTest::a record is sent as the body key and headers and the answer is where it landed`
  for the route's half; the topic's half is `ci/b-03/run.sh`, green on both builds on 2026-09-27

### Scenario: queue full, never queued
* **Given:** the producer's queue is at its bound and stays there past `MOSTIK_QUEUE_WAIT_MS`
* **When:** a client posts
* **Then:** `429` with `Retry-After` and `"error": "not-queued"`
* **And:** after the queue drains, no record with that key is in the topic
* **Automated:** `PublishRoutesTest::a record not queued is 429 with Retry-After` for the route's half; the
  topic's half is `ci/b-05/run.sh`, green on both builds on 2026-09-27: `429` after 1 013 ms (native) and
  1 021 ms (JVM) with a 1 000 ms queue wait, and the record absent after the queue drained

### Scenario: queued, broker silent
* **Given:** the broker is paused after the record is queued
* **When:** the deadline expires
* **Then:** `504` with `"outcome": "unknown"` and `"retrySafe": false`, within the deadline plus the margin
* **And:** after the broker resumes, the record **is** in the topic. This is the scenario that proves
  the word "unknown" is needed (research H1).
* **Automated:** `PublishRoutesTest::a delivery that does not answer within the deadline is 504 outcome-unknown`
  and `the time spent queueing is taken off the deadline` for the route's half; the topic's half is
  `ci/b-05/run.sh`, green on both builds on 2026-09-27: `504` after 3 011 ms and 3 022 ms, and the record found
  in the topic after `resume`

### Scenario: topic not in the allowlist
* **Given:** `audit` is not in `MOSTIK_TOPICS`
* **When:** a client posts to `/topics/audit/records`
* **Then:** `404` with `"error": "topic-not-found"`
* **And:** the producer is never called, and no topic called `audit` exists afterwards
* **Automated:** `PublishRoutesTest::a topic outside the allowlist is 404 and nothing is sent`; end to end in
  `ci/b-03/run.sh`

### Scenario: body too large
* **Given:** `MOSTIK_MAX_RECORD_BYTES` is 1024
* **When:** a client posts 1025 bytes
* **Then:** `413` with `"error": "record-too-large"`, and the producer is not called
* **And:** exactly 1024 bytes is accepted: the limit is "over", not "at"
* **Automated:** `PublishRoutesTest::a body over the limit is 413 and nothing is sent`, and
  `a body exactly at the limit is sent`; end to end in `ci/b-03/run.sh`

### Scenario: two builds, one answer
* **Given:** the native binary and the JVM distribution against the same broker
* **When:** every scenario above runs on each
* **Then:** each gives the same status on both, or this document names the difference per build
* *Checked on both builds on 2026-09-27: acknowledged, 404 and 413 by `ci/b-03/run.sh`, and the two deadline
  scenarios by `ci/b-05/run.sh`. Same statuses everywhere, down to 60 requests filling a full queue: 10 × `504`
  and 50 × `429` on each build.*

## 6. Out of scope

* Authentication, idempotency keys, batches, JSON envelopes, tombstones, reading from Kafka (research §2).

## 7. Quirks

* Record headers arrive grouped by name, not in the order they were sent (endpoint-records, measured).
* `429` comes from `enqueue` throwing, never from a cut wait. A timeout around `enqueue` would answer
  `429` for a record that lands on the JVM (kafkakn B-73, research §1.3).
* A `504` record can be written after the client was told "unknown", when the broker answers later. At
  shutdown `close` gets 3 s (kore's `releaseGroup`), and a record still unacknowledged then is dropped with the
  process (B-08). Either way the client was told the truth: nobody knew.
* **With the broker stopped, not paused, the two builds answer differently.** The topic's metadata was known
  from an earlier publish: the JVM build answered `429` (not queued) five times out of five, and the native
  build `504` (queued) five times out of five (B-08). Both answers are true. They are not the same, and B-05's
  "same status on both builds" was checked for a paused broker and a full queue only.
