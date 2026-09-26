---
id: B-01
title: "From keel's template to a bridge that links kafkakn on both builds"
status: done
priority: P0
size: M
stage: stage-1-skeleton
epic: feature-publish-over-http
---

# B-01 — from keel's template to a bridge that links kafkakn on both builds

The tree is keel's template at `youndie/keel@6be238d`: an `item` feature over SQLite, the package
`io.github.youndie.keel`, and a project named `keel`. mostik has no database and publishes to Kafka.
Feature: [feature-publish-over-http](../features/feature-publish-over-http.md).

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

## Findings (2026-09-27)

Where each check ran: the build and both suites on the Linux box (`wsl-run`, a memory-capped scope, no
shared daemon), the documentation gate on the Mac. There is no remote and no CI; the local merge rule is in
`CLAUDE.md`.

- **AC: `./gradlew build` green, and the native executable links from the reposilite coordinates alone.**
  `BUILD SUCCESSFUL`. The first run used `--refresh-dependencies`, so the kafkakn snapshot is the republished one
  and not a cached copy from 2026-09-25. The test-result XML, not the log line, says what ran: 13 tests on `jvm`
  and 13 on `linuxX64` (`MostikConfigTest` 11, `ProducerConstructionTest` 2), none failed, stamped
  2026-09-26 23:00 UTC. `aotTrain` ran, so the JVM distribution starts. The Linux box has no kafkakn checkout
  beside the replica. The release executable is 14,304,424 bytes, 11,909,976 under the 25 MiB budget; the
  keel's own comment recorded 9,227,448 for its binary with SQLite.
- **AC: `grep -ri keel`** over `server/`, `distribution/`, `Dockerfile` and the Gradle files finds two lines, and
  both name the template's origin on purpose (`server/build.gradle.kts`, the root `build.gradle.kts`).
- **AC: `--print-config`** lists `MOSTIK_BOOTSTRAP_SERVERS`, `MOSTIK_TOPICS`, `MOSTIK_PUBLISH_DEADLINE_MS` and
  `MOSTIK_MAX_RECORD_BYTES`. This was checked on the release binary, and by a test on both targets.
- **The pass-through, through the real path, on both builds:**
  - `KAFKA_ACKS` reaches the producer as `acks`;
  - `KAFKA_ACSK` stops the start-up with exit 1. Native: *"unknown producer configuration: acsk (No such
    configuration property: "acsk")"*. JVM: *"… (kafka-clients knows 113 keys; nothing here is silently
    ignored)"*;
  - `KAFKA_BOOTSTRAP_SERVERS` is refused and names `MOSTIK_BOOTSTRAP_SERVERS`, a decision this item took. One
    value has one source.
- **The shutdown order, on both builds:** `/health/ready` 200, then `SIGTERM`. `ANNOUNCE` took 5.0 s, which is
  kore's `preDrainWait`. `DRAIN` took 2.4 ms on native and 0.5 ms on the JVM. `RELEASE_POOLS`, where the
  producer is closed, took 1.2 ms and 3.3 ms. The exit was 0 on native and 143 on the JVM, the platform
  difference `CLAUDE.md` names. `/items` answers 404.
- **Found: librdkafka connects to the bootstrap servers at construction.** `Connect to ipv4#127.0.0.1:1 failed`
  is logged within a second of start-up, before any record. kafkakn's contract says `rd_kafka_new` "connects to
  nothing". A sentence in this item's code first repeated that, and it was removed before the build, unverified.
  This is for kafkakn to correct; it is recorded in research §1.8.
- **Found: the template's razves workaround was dead.** `server/build.gradle.kts` disabled a task named
  `sizeBudgetCheckDebugExecutable`, and no task has that name. The real one, `sizeBudgetCheckLinuxX64DebugExecutable`,
  ran and reported *"no size rule is set for it, so nothing was checked"* for a 48,974,392-byte debug binary. razves
  now leaves debug alone by itself. The line is replaced by that measured fact. keel carries the same dead line.
- **Also removed with the item store:** `k6/` and the `:server:measure` task. They measured `/items` on keel's
  stand. The training workload of the AOT cache moved to `/version` until B-03 gives it a route.
- **The rename, for youndie/keel#40:** `git diff --numstat main` over `server`, `distribution`, `Dockerfile`, the
  Gradle files, `.github`, `Makefile` and `.gitignore` gives 26 files, +512 −988 lines. Most of it is the removed
  store, its tests and `k6/`, so this is an upper bound on the rename, not its size.
- **A shared-cache note:** on the first run Gradle discarded `modules-2/metadata-2.107/resource-at-url.bin` as
  corrupt on the Linux box. It did not affect the result.
