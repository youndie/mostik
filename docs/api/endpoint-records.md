---
id: endpoint-records
title: Publishing a record
type: api_endpoints
status: active
services:
  - mostik-server
contract_source:
  - mostik:server io.github.youndie.mostik.publish
parent_feature: feature-publish-over-http
---

# API: publishing a record

> Read against the route on 2026-09-27 (B-03). What exists is marked by where it was checked; what is
> *target* says which item makes it real. There is no shared contract module (research D5), so the source of
> truth is the route itself.

## Routes — all of them, no exceptions

| Method and path | Service | Auth tier | In the generated schema? | Purpose |
|---|---|---|---|---|
| `POST /topics/{topic}/records` | mostik-server | none in the service; the proxy authenticates (research D4) | no — mostik generates no schema | publish one record and answer with what happened to it |

## Handlers (code anchors)

| Route | Handler |
|---|---|
| `POST /topics/{topic}/records` | `server/src/commonMain/kotlin/io/github/youndie/mostik/publish/` |

## Request

| Part | Becomes |
|---|---|
| body | the record's value, byte for byte, never parsed; empty is an empty value, not a tombstone |
| `Record-Key` header | the record's key; absent means no key |
| `Record-Header-<name>` headers | record headers; the name after the prefix is kept as sent, case included, and the prefix is matched without regard to case. **Grouped by name, not in arrival order**: sent as `Zeta:1, alpha:2, Zeta:3`, they arrive as `Zeta:1, Zeta:3, alpha:2` — each name at its first appearance, its values in arrival order. Measured on both builds, `ci/b-03/run.sh` |
| `{topic}` | the topic; it must be in `MOSTIK_TOPICS` |

## Responses

| Outcome | Status | Body |
|---|---|---|
| the broker acknowledged | `200` | `{"topic": …, "partition": …, "offset": …, "timestamp": …}`; the reader finds the same bytes, key and header at that place on both builds (`ci/b-03/run.sh`) |
| the record was provably never queued | `429` + `Retry-After` | `{"error": "not-queued", "detail": …}`: `enqueue` threw `RecordNotQueuedException` within `MOSTIK_QUEUE_WAIT_MS`. `Retry-After` is the queue wait in whole seconds, at least 1. On both builds, the record is absent from the topic afterwards (`ci/b-05/run.sh`) |
| the producer refused the record before queueing it, for any other reason (for example it is closed) | `502` | `{"error": "producer-refused", "detail": …}`: not written, and waiting will not help, so not `429` |
| the deadline passed after the record was queued, or the delivery failed after queueing | `504` | `{"error": "outcome-unknown", "detail": …, "outcome": "unknown", "retrySafe": false}`. With the broker paused, the record is found in the topic after it resumes, on both builds (`ci/b-05/run.sh`) |
| the broker named a refusal (for example the record is too large for the topic) | `502` | `{"error": "broker-rejected", "detail": …}` — *target*; **whether this is reachable is B-06**. Today a refusal after queueing is a `504` like any other failure of the delivery |

## Errors

| Condition | Status | Body |
|---|---|---|
| `{topic}` not in `MOSTIK_TOPICS` | `404` | `{"error": "topic-not-found", "detail": …}`; the producer is never called |
| body over `MOSTIK_MAX_RECORD_BYTES` | `413` | `{"error": "record-too-large", "detail": …}`; the producer is never called |
| the service is draining (not during the announce, when it still serves) | `503` | kore's plain text `shutting down\n`, with `Connection: close` — **not** the JSON shape (research §1.6) |

The `error` code is contract and `detail` is not. The status set is closed: no route here answers `500`
by design (research D2).
