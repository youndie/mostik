---
id: B-01
title: "From keel's template to a bridge that links kafkakn on both builds"
status: open
priority: P0
size: M
stage: stage-1-skeleton
---

# B-01 — from keel's template to a bridge that links kafkakn on both builds

The tree is keel's template at `youndie/keel@6be238d`: an `item` feature over SQLite, the package
`io.github.youndie.keel`, and a project named `keel`. mostik has no database and publishes to Kafka.
Feature: publish over HTTP.

- **The decision and its reason.** Rename to `mostik` (`rootProject.name`, the package, the binary, the
  image), remove the `item` feature and sqlx4k, and depend on `io.github.youndie.kafkakn:kafkakn-core:0.1.0-SNAPSHOT`.
  The producer takes the SQLite pool's place as the `ShutdownParticipant` in the wiring
  (research §1.6).
- **No toolchain move.** The brief planned one, and research §1.7 shows that both repositories already
  take the compiler from the shared catalog.
- Not covered: any route. The producer is constructed and closed, and nothing sends yet.

- AC: `./gradlew build` is green on the Linux box, and the native executable **links** from the
  reposilite coordinates alone, with no kafkakn checkout beside it. Research §1.8 records the one time
  that was false.
- AC: `grep -ri keel` over `server/`, `distribution/`, `Dockerfile` and the Gradle files finds only
  deliberate mentions of the template's origin.
- AC: `--print-config` lists `MOSTIK_BOOTSTRAP_SERVERS`, `MOSTIK_TOPICS`, `MOSTIK_PUBLISH_DEADLINE_MS` and
  `MOSTIK_MAX_RECORD_BYTES`. A `KAFKA_*` variable reaches the producer as its dotted key, and a
  misspelt one stops the start-up (research §1.10).
- Anchors: `settings.gradle.kts`, `gradle/libs.versions.toml`, `server/build.gradle.kts`,
  `server/src/commonMain/kotlin/io/github/youndie/keel/Wiring.kt`, `Dockerfile`.
