---
id: feature-publish-over-http
title: Publish a record over HTTP, and report its real outcome
type: feature
status: draft
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

* `200` is sent only after `send` returned the broker's metadata, never after the record was merely
  queued.
* A record answered `429` or `503` is never in the topic.
* A record answered `504` may or may not be in the topic, and the body says `"retrySafe": false`.
* Every request ends within `MOSTIK_PUBLISH_DEADLINE_MS` plus a fixed margin; none waits out a producer
  timeout (research §1.4).
* A topic outside `MOSTIK_TOPICS` and a body over `MOSTIK_MAX_RECORD_BYTES` are refused before the
  producer is called.
* No request answers `500` (research D2).

## 3. Flow

1. The route checks the topic against the allowlist and the body against the size limit.
2. It builds a `ProducerRecord` from the body, `Record-Key` and `Record-Header-*` (research D1).
3. `withTimeout(deadline) { producer.send(record) }`.
4. Metadata becomes `200`; an expiry becomes `429` or `504` (B-05); a named refusal becomes `502`
   (B-06); anything else becomes `504`.

## 4. Code anchors

| Service | Code |
|---|---|
| mostik-server | `server/src/commonMain/kotlin/io/github/youndie/mostik/publish/` — the route and the outcome mapping |
| mostik-server | `server/src/commonMain/kotlin/io/github/youndie/mostik/MostikConfig.kt` — allowlist, deadline, size limit |
| scenarios' oracle | `ci/broker/` — reads the topic; neither mostik nor kafkakn is asked (B-02) |

## 5. Scenarios (BDD / test cases)

All *target*. Each runs on both builds, and each is decided by reading the topic, not by asking mostik.
Sample data: the topic `orders` (3 partitions), key `order-1042`, value
`{"orderId":1042,"amount":"19.90","currency":"EUR"}`, header `trace-id: 7f3a9c`, deadline 5000 ms.

### Scenario: acknowledged
* **Given:** `orders` is in `MOSTIK_TOPICS` and the broker is up
* **When:** a client posts the sample value to `/topics/orders/records` with `Record-Key: order-1042`
  and `Record-Header-trace-id: 7f3a9c`
* **Then:** `200` with `topic`, `partition`, `offset`, `timestamp`
* **And:** the topic holds exactly those bytes, that key and that header at that partition and offset

### Scenario: queue full, never queued
* **Given:** the producer's queue is at its bound and stays there past the deadline
* **When:** a client posts
* **Then:** `429` with `Retry-After` and `"error": "not-queued"`
* **And:** after the queue drains, no record with that key is in the topic
* *Unreachable until B-04; may stay unreachable on the JVM build (research §1.3).*

### Scenario: queued, broker silent
* **Given:** the broker is paused after the record is queued
* **When:** the deadline expires
* **Then:** `504` with `"outcome": "unknown"` and `"retrySafe": false`, within the deadline plus the margin
* **And:** after the broker resumes, the record **is** in the topic. This is the scenario that proves
  the word "unknown" is needed (research H1).

### Scenario: topic not in the allowlist
* **Given:** `audit` is not in `MOSTIK_TOPICS`
* **When:** a client posts to `/topics/audit/records`
* **Then:** `404` with `"error": "topic-not-found"`
* **And:** the producer's `metrics()` shows no record sent

### Scenario: body too large
* **Given:** `MOSTIK_MAX_RECORD_BYTES` is 1024
* **When:** a client posts 1025 bytes
* **Then:** `413` with `"error": "record-too-large"`, and the producer is not called

### Scenario: two builds, one answer
* **Given:** the native binary and the JVM distribution against the same broker
* **When:** every scenario above runs on each
* **Then:** each gives the same status on both, or this document names the difference per build

## 6. Out of scope

* Authentication, idempotency keys, batches, JSON envelopes, tombstones, reading from Kafka (research §2).

## 7. Quirks

* `429` is documented and unreachable until B-04 (research §1.3).
* A `504` record can be written after the client was told "unknown", even during shutdown: `close`
  flushes it. That is what "unknown" means.
