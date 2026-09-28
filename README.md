# mostik

**An HTTP → Kafka bridge whose status code is a true statement about the record.**

```
POST /topics/orders/records   →   200 {"topic":"orders","partition":0,"offset":196,"timestamp":1790529445500}
```

| Status | What it says about the record | Retry? |
|---|---|---|
| `200` | the broker acknowledged it; the body names the partition and offset | — |
| `429` | it was provably never queued (`Retry-After` says when to try again) | safe |
| `502` | the producer refused it before queueing, for example because it is closed | safe, though waiting will not help |
| `503` | the service is draining and did not take it | safe |
| `504` | it was queued, and then the deadline passed or the delivery failed: **it may or may not be written** | may write it twice |
| `404`, `413` | the topic is not on the allowlist, or the body is over the size limit; the producer was never called | no |

The usual bridge answers `200` once the record is queued in its local client. After that it either waits for the
broker without a bound, or cuts the wait and answers `503` for a record that lands anyway. mostik waits for the
broker's acknowledgement under a deadline. When it cannot know what happened, it answers `504`, and the body says
so: `"outcome": "unknown", "retrySafe": false`. The set is closed: no route here answers `500`.

*mostik* is Russian for "a small bridge".

> **Status: `200`, `429` and `504` are each checked by reading the topic with somebody else's client, on both
> builds.** The shutdown is checked the same way. Clients publish under load while the service gets `SIGTERM`, and every
> client's ledger is compared with what the topic holds afterwards. That gave zero disagreements in 20 rounds per
> build (2026-09-27, [B-14](docs/backlog/B-14-take-kore-0-1-7-and-remeasure-the-shutdown.md)).
>
> **One known limit:** on the native build, Ktor's CIO engine occasionally closes a connection without sending the
> answer the route already wrote: between once in 60 000 and once in 375 000 requests under 64 clients. The client then has no answer,
> and must treat it like a `504`. The JVM build has shown none. It reproduces with Ktor alone, in
> [ktor-cio-empty-reply-repro](https://github.com/youndie/ktor-cio-empty-reply-repro)
> ([B-15](docs/backlog/B-15-an-empty-reply-for-a-written-record.md)).

## One request

```bash
curl -i -X POST http://localhost:8080/topics/orders/records \
  -H 'Record-Key: order-42' \
  -H 'Record-Header-Source: checkout' \
  --data-binary '{"id":42,"total":"19.90"}'
```

```
HTTP/1.1 200 OK
Content-Type: application/json

{"topic":"orders","partition":0,"offset":196,"timestamp":1790529445500}
```

- The body is the record's value, byte for byte, and is never parsed.
- `Record-Key` is the key; leave it out for no key.
- Each `Record-Header-<name>` becomes a record header named `<name>`.

The full contract, with every error body, is [docs/api/endpoint-records.md](docs/api/endpoint-records.md).

What mostik does not do is on purpose: authentication (the proxy in front does it, and `MOSTIK_TOPICS` is the
allowlist), idempotency keys, batches, tombstones, and reading from Kafka.

## Running it

It ships twice from one source: a Kotlin/Native `linuxX64` binary, and a JVM distribution beside it. Both need a
broker. `ci/broker/broker.sh up` starts one in Docker on `127.0.0.1:19092`, with the topics `orders` and `payments`.

```bash
ci/broker/broker.sh up

./gradlew :server:linkReleaseExecutableLinuxX64            # native; builds on Linux x86-64
MOSTIK_BOOTSTRAP_SERVERS=127.0.0.1:19092 MOSTIK_TOPICS=orders,payments \
  server/build/bin/linuxX64/releaseExecutable/mostik.kexe

./gradlew :distribution:installDist                       # JVM, any host with a JDK
MOSTIK_BOOTSTRAP_SERVERS=127.0.0.1:19092 MOSTIK_TOPICS=orders,payments \
  distribution/build/install/distribution/bin/distribution
```

The image carries the native binary on `gcr.io/distroless/cc-debian13`: `docker build -t mostik .`

Beside the route, kore's probes: `/health/startup`, `/health/ready`, `/health/live`, and `/version`. A proxy
should route by **`/health/ready`**. `/health` is an alias for liveness, and a readiness check pointed there can
never fail while the process is alive.

### Configuration

Everything comes from the environment. A variable under `MOSTIK_` that is not in this table stops the start, and
so does a budget that cannot hold (below).

| Variable | Default | |
|---|---|---|
| `MOSTIK_BOOTSTRAP_SERVERS` | required | Kafka's `bootstrap.servers` |
| `MOSTIK_TOPICS` | required | the allowlist, comma-separated; any other topic is `404` |
| `MOSTIK_PORT` | `8080` | |
| `MOSTIK_PUBLISH_DEADLINE_MS` | `5000` | the bound on one publish, from the request to the answer |
| `MOSTIK_QUEUE_WAIT_MS` | `1000` | how long a record may wait for room in the queue or for topic metadata before it is `429`; the producer's `max.block.ms` |
| `MOSTIK_DRAIN_MS` | `15000` | how long requests in flight get after `SIGTERM` |
| `MOSTIK_MAX_RECORD_BYTES` | `1048576` | a larger body is `413` |
| `MOSTIK_TRACY_ENDPOINT`, `MOSTIK_TRACY_KEY` | unset | logs to a [tracy](https://github.com/youndie/tracy) collector; both or neither |
| `KAFKA_*` | | producer settings, passed through: `KAFKA_SSL_CA_LOCATION` → `ssl.ca.location` |

The start is refused, with both values named, when:

- `MOSTIK_DRAIN_MS` is shorter than `MOSTIK_PUBLISH_DEADLINE_MS` plus one second. The drain would then cut
  requests that still have time to be answered.
- `MOSTIK_QUEUE_WAIT_MS` is not shorter than the deadline. Nothing would then be left for the broker's answer.
- `KAFKA_BOOTSTRAP_SERVERS` or `KAFKA_MAX_BLOCK_MS` is set. mostik sets those itself, and one value with two
  sources is a disagreement waiting to happen.
- The port is taken. The refusal is one line naming it, not a stack trace.

A `KAFKA_*` key that neither producer understands is refused when the producer is built.

### Shutdown

On `SIGTERM`, kore runs the stop in three steps:

1. **Announce**, 5 s: readiness answers `503` so the proxy stops sending, but requests keep being served.
2. **Drain**: new requests get `503`, and those in flight finish within their deadline.
3. **Release**: the producer is closed. If the broker is gone, kore cuts the close after 3 s, so the process still
   exits within its grace period.

A clean stop exits `0` on native and `143` on the JVM.

## What it is built on

- **[kafkakn](https://github.com/youndie/kafkakn)** is the producer: librdkafka on the native build, the official
  Java client on the JVM. mostik splits each publish in two. First, `enqueue`, bounded by `max.block.ms`: a
  failure there is provably not written, so it is `429`. Then `await`, under what is left of the deadline: a
  failure there is `504`. The first half of that API exists because mostik asked for it (youndie/kafkakn#94).
- **[kore](https://github.com/youndie/kore)** runs the lifecycle: the configuration schema, the probes, and the
  ordered shutdown.
- **[keel](https://github.com/youndie/keel)** is the template it started from: a Ktor CIO server built for both
  targets into one image.

Every scenario runs on both builds, so the JVM build is the oracle for the native one. Where the two disagree, it
is written down. For example, at the moment a broker stops, either build can answer `429` or `504` for the next
publish, because neither client has noticed yet; both answers are true
([B-17](docs/backlog/B-17-take-kafkakn-0-1-0-13.md)).

**kafkakn, kore, sborka and razves are not on Maven Central.** `settings.gradle.kts` already declares their
repository, filtered to their group:

```kotlin
maven("https://reposilite.kotlin.website/snapshots") {
    content { includeGroupByRegex("io\\.github\\.youndie.*") }
}
```

## Documentation

[docs/](docs/README.md), in the [docs-bootstrap](https://github.com/youndie/docs-bootstrap) format.
[Research](docs/research/research-architecture.md) explains why the answers are shaped this way, and separates
what was verified from what was assumed. [Features](docs/features/) hold the scenarios that are the acceptance.
[The API](docs/api/) lists every route and status, and [the service document](docs/services/mostik-server.md)
covers the build and its quirks. [backlog.md](backlog.md) is the order the work was done in: each item says what
was measured, where, and what it found.

```bash
pip install pyyaml
make check     # the documentation gate CI runs
make build     # both targets and both test suites
```

## Licence

MIT.
