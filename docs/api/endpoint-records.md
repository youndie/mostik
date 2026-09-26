---
id: endpoint-records
title: Publishing a record
type: api_endpoints
status: draft
services:
  - mostik-server
contract_source:
  - mostik:server io.github.youndie.mostik.publish
parent_feature: feature-publish-over-http
---

# API: publishing a record

> **Draft.** Every status and body below is *target*, decided in research D2, and is re-read from the
> route code before this document goes `active`. There is no shared contract module (research D5), so
> the source of truth is the route itself.

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
| `Record-Header-<name>` headers | record headers, in the order they arrived; the name after the prefix is kept as sent |
| `{topic}` | the topic; it must be in `MOSTIK_TOPICS` |

## Responses

| Outcome | Status | Body |
|---|---|---|
| the broker acknowledged | `200` | `{"topic": …, "partition": …, "offset": …, "timestamp": …}` |
| the record was provably never queued | `429` + `Retry-After` | `{"error": "not-queued", "detail": …}` — `enqueue` threw `RecordNotQueuedException` within `MOSTIK_QUEUE_WAIT_MS`; arrives with B-04 |
| the deadline passed after the record was queued, or `send` threw something mostik cannot classify | `504` | `{"error": "outcome-unknown", "detail": …, "outcome": "unknown", "retrySafe": false}` |
| the broker named a refusal (for example the record is too large for the topic) | `502` | `{"error": "broker-rejected", "detail": …}` — **whether this is reachable is B-06** |

## Errors

| Condition | Status | Body |
|---|---|---|
| `{topic}` not in `MOSTIK_TOPICS` | `404` | `{"error": "topic-not-found", "detail": …}`; the producer is never called |
| body over `MOSTIK_MAX_RECORD_BYTES` | `413` | `{"error": "record-too-large", "detail": …}`; the producer is never called |
| the service is shutting down | `503` | kore's plain text `shutting down\n`, with `Connection: close` — **not** the JSON shape (research §1.6) |

The `error` code is contract and `detail` is not. The status set is closed: no route here answers `500`
by design (research D2).
