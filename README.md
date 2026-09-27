# mostik

**An HTTP → Kafka bridge whose status code is a true statement about the record.**

| Status | Means |
|---|---|
| `200` | the broker acknowledged it; the body names the partition and offset |
| `429` | it was provably never queued; retrying is safe |
| `503` | the service is shutting down and did not take it |
| `504` | the deadline passed after it was queued; it may or may not be written, and a retry may write it twice |

The usual bridge answers `200` once the record is queued in its local client. It then either waits for
the broker without a bound, or cuts the wait and answers `503` for a record that lands anyway. mostik
waits for the acknowledgement under a deadline, and says `504` when it does not know.

*mostik* is Russian for "a small bridge".

> **Status: every status above works on both builds, and each was checked by reading the topic.** What is
> left is proving the shutdown under load. The plan, and the reason the statuses are shaped this way, is in
> [docs/research](docs/research/research-architecture.md). The order of work is in
> [backlog.md](backlog.md).

## What it is built on

- **[keel](https://github.com/youndie/keel)**: a Ktor server that ships twice, as a Kotlin/Native binary
  and as a JVM distribution in one image, with [kore](https://github.com/youndie/kore)'s ordered shutdown.
  On `SIGTERM` mostik stops being ready, refuses new requests, lets the ones in flight finish within
  their deadline, and only then closes the producer.
- **[kafkakn](https://github.com/youndie/kafkakn)**: the producer. It uses librdkafka on the native
  build and the official Java client on the JVM build. Every scenario runs on both builds, so the JVM
  build is the oracle for the native one.

What mostik needs from kafkakn that kafkakn does not yet say is filed there: what a cancelled `send`
leaves behind, and whether it was queued at all (youndie/kafkakn#94).

## Resolving the dependencies

**kafkakn, kore, sborka and razves are not on Maven Central.** A build needs the portfolio's repository,
filtered to its group. `settings.gradle.kts` already has it:

```kotlin
maven("https://reposilite.kotlin.website/snapshots") {
    content { includeGroupByRegex("io\\.github\\.youndie.*") }
}
```

## Documentation

[docs/](docs/README.md), in the [docs-bootstrap](https://github.com/youndie/docs-bootstrap) format:
research, features with BDD scenarios, the API, the service. The layer documents are drafted and
reach `main` together with the code they describe.

## Licence

MIT.
