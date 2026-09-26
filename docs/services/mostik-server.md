---
id: mostik-server
title: mostik server — the bridge, built twice
type: service
status: draft
module: server, distribution
tech_stack: [Kotlin Multiplatform, Kotlin/Native linuxX64, JVM, Ktor CIO, kore, kafkakn]
owner: unassigned
depends_on: [kafka]
publishes: [container image with the native binary and the JVM distribution]
---

# mostik server

> **Draft.** Everything below is *target* until B-01 and B-03 give it code. The paths are where the
> code will live after B-01 renames the template's package from `keel` to `mostik`.

## 1. Responsibility

Takes one HTTP request, publishes its body as one Kafka record, and answers with a status that is a
true statement about that record ([feature-publish-over-http](../features/feature-publish-over-http.md)).
Stops without making any answer false ([feature-shutdown-without-loss](../features/feature-shutdown-without-loss.md)).

**Deliberately does not:** authenticate (the reverse proxy does, research D4); store anything (no
database, no outbox — research §2); deduplicate (a `504` says a retry is unsafe, research D2); batch;
read from Kafka.

## 2. API contracts

[endpoint-records](../api/endpoint-records.md) — publishing. [endpoint-probes](../api/endpoint-probes.md)
— kore's probes and `/version`, as keel mounts them.

## 2a. Code anchors

| File | What is there |
|---|---|
| `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt` | the producer built at start-up, the routes mounted, the kore shutdown sequence with the producer as its participant |
| `server/src/commonMain/kotlin/io/github/youndie/mostik/MostikConfig.kt` | the `MOSTIK_` schema and the `KAFKA_` pass-through |
| `server/src/commonMain/kotlin/io/github/youndie/mostik/publish/` | the route and the outcome mapping |
| `distribution/` | the JVM distribution, keel's convention unchanged |
| `Dockerfile` | one image, both builds |
| `ci/broker/` | the broker fixture and the topic reader the scenarios use as their oracle (B-02) |

## 3. How it is built

**The deadline is mostik's, not the producer's.** `send` runs under `withTimeout(PUBLISH_DEADLINE_MS)`.
No producer key bounds the whole call on both arms (research §1.4). So the bound lives where both
builds share it, and the producer keys only shape what happens underneath.

**An expiry is translated, never rethrown.** The coroutine's `TimeoutCancellationException` becomes
`429` or `504`, depending on what kafkakn reports about whether the record was queued (B-05). Until
kafkakn B-74 is taken (B-04), it is always `504`. The translation happens inside the route. A
`TimeoutCancellationException` that escaped to Ktor would be a `500`, the one status research D2
excludes.

**The shutdown order is keel's, with the producer in the SQLite pool's slot** (research §1.6): not ready,
then refusal, then the engine drain, then `producer.close()` as a `ShutdownParticipant`. The drain has to
outlast the deadline, and the start-up refuses a configuration where it does not (B-07).

**Two builds, one source.** The native binary links librdkafka from inside the kafkakn klib; the JVM
distribution runs the official Java client. Neither build carries code of its own for publishing.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Library | `io.github.youndie.kafkakn:kafkakn-core:0.1.0-SNAPSHOT` | the producer, both arms (research §1.8) |
| Library | kore `0.1.4` (`kore-core`, `kore-ktor`) | configuration, probes, `/version`, the shutdown sequence |
| External | Kafka | the only thing written to |
| In front | a reverse proxy | authentication, TLS; its upstream timeout must exceed the deadline (research, risk 1) |

## 5. Infrastructure and deploy

Not decided. The image is keel's, renamed.

## 6. Local setup

*Target, B-02:* the broker from `ci/broker/`, then the service with `MOSTIK_BOOTSTRAP_SERVERS` pointing at
it. Gradle runs on the Linux box; see `CLAUDE.md`, *Where things build*.

## 7. Configuration

Read under the prefix `MOSTIK`. kore refuses an undeclared `MOSTIK_*` variable (research §1.10).

| Key | Description | Required |
|---|---|---|
| `MOSTIK_PORT` | listening port; default `8080` | no |
| `MOSTIK_BOOTSTRAP_SERVERS` | Kafka's `bootstrap.servers` | yes |
| `MOSTIK_TOPICS` | the allowlist, comma-separated; any other topic is `404` | yes |
| `MOSTIK_PUBLISH_DEADLINE_MS` | the bound on one publish; default `5000` (*target*) | no |
| `MOSTIK_MAX_RECORD_BYTES` | a larger body is `413` before `send` | no |
| `MOSTIK_TRACY_ENDPOINT`, `MOSTIK_TRACY_KEY` | observability, both or neither, as in keel | no |
| `KAFKA_*` | producer keys, outside the schema: `KAFKA_ACKS` → `acks`. kafkakn refuses a key neither arm honours | no |

## 8. Quirks

- **The `503` during shutdown is not mostik's.** kore's refusal answers `503` with the text
  `shutting down\n` and `Connection: close`, before any mostik code runs. It is the one error without
  mostik's JSON body (research §1.6, consequence 3).
- **`429` is in the contract and unreachable** until B-04 takes kafkakn B-74. It may stay unreachable
  on the JVM build even then (research §1.3, consequence 2).
- **A `504` record may still be in the producer when the process exits.** `close` flushes it, so it can
  be written after the client was told "unknown". That is consistent with "unknown". How long `close`
  takes with the broker gone is B-08.
