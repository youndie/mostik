---
id: mostik-server
title: mostik server — the bridge, built twice
type: service
status: active
module: server, distribution
tech_stack: [Kotlin Multiplatform, Kotlin/Native linuxX64, JVM, Ktor CIO, kore, kafkakn]
owner: unassigned
depends_on: [kafka]
publishes: [container image with the native binary and the JVM distribution]
---

# mostik server

> Read against the code on 2026-09-27 (B-03). What is not built yet is marked *target* with the item that
> builds it. Open at shutdown: reset connections (B-11) and the JVM build's early close (B-12).

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

**The deadline is split into kafkakn's two steps** (research §1.4, correction; B-05):

1. `enqueue(record)` runs with **no** coroutine timeout, bounded by `max.block.ms`, which mostik sets from
   `MOSTIK_QUEUE_WAIT_MS`. `RecordNotQueuedException` becomes `429`; any other failure there is `502
   producer-refused`, because the record was not queued and waiting will not help.
2. `delivery.await()` runs under `withTimeout(PUBLISH_DEADLINE_MS − time spent in step 1)`. An expiry
   becomes `504`.

A timeout around step 1 is the obvious code, and it is wrong. On the JVM, the Java client does not give
the thread back while it waits for room, so the cut is honoured seconds late, and the record may be queued
by then. kafkakn measured this in B-73.

**An expiry is translated, never rethrown.** The `TimeoutCancellationException` from step 2 becomes `504`
inside the route. Escaping to Ktor, it would be a `500`, the one status research D2 excludes. The clock step 1
is timed with is a parameter of the route (`TimeSource`), so a test makes step 1 take most of the deadline
without waiting for it.

**`Retry-After` on `429` is the queue wait, rounded up to whole seconds, at least 1.** A retry sooner asks the
same full queue again. Nothing measures how fast the queue drains, so a number derived from the drain would be a
number derived from nothing (research, open question 2).

**The shutdown order is keel's, with the producer in the SQLite pool's slot** (research §1.6): not ready,
then refusal, then the engine drain, then `producer.close()` as a `ShutdownParticipant`. The drain has to
outlast the deadline plus a 1 000 ms margin, and the start-up refuses a configuration where it does not
(B-07). The drain is `MOSTIK_DRAIN_MS`, handed to kore's `ShutdownDeadlines(drain = …)`; the other deadlines
stay kore's defaults.

**The route takes `send` as a function**, not the producer, so its own decisions are tested without a broker
(`PublishRoutesTest`). What the broker did is only ever read out of the topic (`ci/b-03/run.sh`).

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

On the Linux box, which has Docker and runs Gradle (see `CLAUDE.md`, *Where things build*):

1. `ci/broker/broker.sh up` starts `apache/kafka:4.3.1` as `mostik-broker` on `127.0.0.1:19092`, waits until
   it answers, and creates `orders` (3 partitions) and `payments` (1). `ci/broker/broker.sh selftest` is its
   acceptance (B-02).
2. Start the service with `MOSTIK_BOOTSTRAP_SERVERS=127.0.0.1:19092 MOSTIK_TOPICS=orders,payments`.
3. Read what arrived with `ci/broker/broker.sh at <topic> <partition> <offset>` or `key <topic> <key>`. That is
   the broker distribution's own consumer, never mostik's producer. Nothing can be read while the broker is
   `pause`d.

## 7. Configuration

Read under the prefix `MOSTIK`. kore refuses an undeclared `MOSTIK_*` variable (research §1.10).

| Key | Description | Required |
|---|---|---|
| `MOSTIK_PORT` | listening port; default `8080` | no |
| `MOSTIK_BOOTSTRAP_SERVERS` | Kafka's `bootstrap.servers` | yes |
| `MOSTIK_TOPICS` | the allowlist, comma-separated; any other topic is `404` | yes |
| `MOSTIK_PUBLISH_DEADLINE_MS` | the bound on one publish; default `5000`. Enforced by the route (B-05) | no |
| `MOSTIK_DRAIN_MS` | how long the engine drains on `SIGTERM`; default `15000`, kore's own. Must be at least the deadline plus 1 000 ms. Shrink it, and the deadline with it, where the grace period is short: `docker stop` gives 10 s | no |
| `MOSTIK_QUEUE_WAIT_MS` | becomes the producer's `max.block.ms`, which bounds `enqueue`; must be shorter than the deadline; default `1000`. It is also `Retry-After` on a `429`, in seconds | no |
| `MOSTIK_MAX_RECORD_BYTES` | a larger body is `413` before `send`; default `1048576` | no |
| `MOSTIK_TRACY_ENDPOINT`, `MOSTIK_TRACY_KEY` | observability, both or neither, as in keel | no |
| `KAFKA_*` | producer keys, outside the schema: `KAFKA_ACKS` → `acks`. kafkakn refuses a key neither arm honours | no |

## 8. Quirks

- **The `503` during shutdown is not mostik's.** kore's refusal answers `503` with the text
  `shutting down\n` and `Connection: close`, before any mostik code runs. It is the one error without
  mostik's JSON body (research §1.6, consequence 3).
- **The kafkakn version is pinned, and the pin is load-bearing.** `0.1.0.11` is the first version in which native
  `enqueue` refuses a record whose topic has no metadata (kafkakn B-76). `EnqueueContractTest` fails on native
  against `0.1.0.10`, so a pin moved back is caught by the suite (B-04).
- **`max.block.ms` is mostik's, not the operator's.** A `KAFKA_MAX_BLOCK_MS` stops the start-up, because the
  queue wait has one source, `MOSTIK_QUEUE_WAIT_MS` (B-05).
- **The queue's bound is a platform key.** Filling the queue on purpose needs `KAFKA_QUEUE_BUFFERING_MAX_MESSAGES`
  on the native build and `KAFKA_BUFFER_MEMORY` on the JVM build, and each build refuses the other's key
  (`ci/b-05/run.sh`).
- **The JVM build stops listening at `SIGTERM`.** Native answers kore's `503` through the 5 s announce, while the
  JVM build refuses connections from 1 ms after the signal. A readiness probe there stops answering rather than
  turning `503` (B-12, likely keel's or kore's).
- **A few requests at shutdown get a reset connection**: 1 to 9 per round under 64 clients, on both builds, and
  no written record behind any of them (B-11).
- **Record headers arrive grouped by name**, not in the order they were sent (endpoint-records, measured).
- **The AOT cache is trained on `/version` only.** Training runs with no broker, so a publish in its workload
  would wait out `max.block.ms` and teach the cache the refusal path. The publishing path is therefore not in
  the cache.
- **`close` gets 3 s, and then the process exits without it.** kore gives each release stage `releaseGroup`
  (3 s by default), cancels a participant still running at the deadline, and does not wait for it. With the broker
  stopped and five records queued, the native build logged `RELEASE_POOLS DEADLINE_EXCEEDED in 3.0s` and exited
  8.1 s after `SIGTERM`: announce 5 s, drain, release 3 s. The five records never reached the topic. Each was a
  `504`, so no answer became false; the transcript names the cut (B-08).
