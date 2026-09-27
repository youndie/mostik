---
id: B-04
title: "Take a kafkakn snapshot whose contract says what a cancelled send leaves behind"
status: done
priority: P0
size: S
stage: stage-3-bounded-wait
epic: feature-publish-over-http
blocked_by: [B-01]
---

# B-04 — take a kafkakn snapshot whose contract says what a cancelled `send` leaves behind

**Blocked outside this repository**, and that is why the item exists. `blocked_by` names only this
repository's items, and without this item the dependency would be invisible to the index. Feature: [feature-publish-over-http](../features/feature-publish-over-http.md).

mostik's deadline answers depend on two kafkakn items, filed in youndie/kafkakn#94:

- kafkakn B-73 writes into the contract what a cancelled `send` means for its record, and measures it
  on both arms (research §1.2, H1);
- kafkakn B-74 lets a caller whose wait was cut tell "never queued" from "queued, outcome unknown"
  (research §1.3).

**Both merged on 2026-09-27** (kafkakn #95 and #96, `fe4f1c4`). B-73 measured that a record cut after
queueing lands, and that on the JVM a cut while waiting for room is not honoured. B-74 added
`enqueue(record): Delivery` and `RecordNotQueuedException`, bounded by `max.block.ms` on both arms
(research §1.3, settled).

The published snapshot is still from 2026-09-25 and holds neither. A merged pull request is not a
publication: kafkakn republishes its snapshot by hand, by running its `publish` workflow (research §1.8).

- **The decision and its reason.** This item is done when the snapshot mostik resolves carries B-74's
  API. mostik does not build kafkakn from a checkout, because the published klib is what a stranger
  links (research §1.8, consequence 1).

- AC: `maven-metadata.xml` for `kafkakn-core` shows a `lastUpdated` after 2026-09-27, when kafkakn B-74
  merged. mostik compiles a call to `enqueue`, `Delivery.await()` and `RecordNotQueuedException` on both
  builds.
- Anchors: `gradle/libs.versions.toml`.

## Progress (2026-09-27)

- **The first half of the AC is met.** The snapshot was republished by kafkakn's `publish` run of
  2026-09-26 22:40 UTC, built from `fe4f1c4`, the B-74 merge. `maven-metadata.xml` now says
  `lastUpdated 20260926224549`, and the build is `0.1.0-20260926.224535-6`, the same for `kafkakn-core-jvm`
  and `kafkakn-core-linuxx64`. The published JVM jar holds `io/github/youndie/kafkakn/Delivery.class` and
  `RecordNotQueuedException.class`. This was read from the jar itself, not inferred from the date.
- **The second half waits on B-01**, which is this item's blocker: mostik does not depend on kafkakn until
  then, so there is nothing yet to compile a call to `enqueue` against.

## Iteration 1 (2026-09-27): the contract is wrong on one arm, and that is the question

- **Both halves of the AC can be met.** mostik resolves the republished snapshot. A test calling
  `enqueue(...).await()` and expecting `RecordNotQueuedException` compiled and ran on both builds.
- **What it found.** kafkakn's contract says `enqueue` throws `RecordNotQueuedException` on both arms when
  there is *no room in the queue, or no metadata*, within `max.block.ms`. B-74 measured only the queue-full
  half. The metadata half was measured here, on the Linux box, with a bootstrap address nobody listens on and
  `max.block.ms` 1 000:

  | | JVM | native |
  |---|---|---|
  | `enqueue` | threw `RecordNotQueuedException` at 1 070 ms (*"Topic orders not present in metadata after 1000 ms"*) | **returned a `Delivery` at 0 ms**: queued, with no metadata |
  | `close()` afterwards | 13 ms (nothing was queued) | **300 200 ms**: it waited out `message.timeout.ms`, 300 000 by default |

  The first run had a 15 s bound around `enqueue(...).await()` and took 299 s on native. The two numbers
  above come from a second, diagnostic run that timed `enqueue` and `close` separately. That test is not
  kept: a suite that takes five minutes and fails on one arm cannot go to `main`.
- **What it means for mostik.** Where the topic's metadata is missing (the broker unreachable, or a topic in
  the allowlist that does not exist), the JVM build answers `429` (truthfully: never queued), and the native
  build would answer `504` after the deadline (also truthfully: the record is queued, and it lands if the broker
  comes back within `message.timeout.ms`). Both answers are true. They are different, and B-05's criterion
  "both builds give the same status" fails in this case. Separately, research H2 is confirmed on native: an
  unreachable broker makes `close` take 300 s against a 30 s grace period. That is B-08's measurement, arriving
  early.
- **The choices, for the owner:**
  1. **Fix it in kafkakn:** the native `enqueue` waits for the topic's metadata up to `max.block.ms` and throws
     `RecordNotQueuedException`, as the contract says. Both builds then answer `429`. This item waits for a
     republished snapshot again. It matches research D7, "the kafkakn changes land in kafkakn".
  2. **Correct kafkakn's contract to the measured behaviour,** and let mostik document the difference per build
     (native `504`, JVM `429` for missing metadata). This item closes now. B-05's "same status" becomes
     "same status, or the difference named".
  3. **Either, plus a start-up check in mostik:** `partitionsFor` each allowlisted topic before serving, so a
     misspelt topic in `MOSTIK_TOPICS` fails the start-up instead of reaching `enqueue`. This narrows the
     difference to "broker unreachable" and does not remove it.

  The recommendation is 1, with 3 as a separate item if it is wanted: the library's promise is what lets
  mostik say one thing on both builds. **The owner decides.** The loop does not pick this item until then, and
  B-05 stays blocked on it.

## Decision (2026-09-27): choice 1

**The owner chose to fix it in kafkakn.** It is filed there as B-76, *"Native enqueue refuses a record
whose topic has no metadata within max.block.ms, as the contract says"* (youndie/kafkakn#98). Both builds
will then answer `429` when metadata is missing.

What this item now waits for, outside this repository:

1. kafkakn B-76 merged;
2. **a numbered kafkakn version** that carries it. Since kafkakn B-75 (#97), every publish gets its own
   number, `0.1.0.<n>`, instead of overwriting `0.1.0-SNAPSHOT`. So this item pins that number in
   `gradle/libs.versions.toml`. A pinned number cannot quietly move under a build, and a day-cached snapshot can
   (research §1.8).

**The loop does not pick this item until the registry lists such a version.** It is `open`, and its blocker
is outside `blocked_by`'s reach. The check is cheap: `maven-metadata.xml` for `kafkakn-core`, then the version's
changelog or commit.

The acceptance then adds one line to the two above: the test that found the gap (`enqueue` with no metadata
within `max.block.ms` throws `RecordNotQueuedException`) runs green on both builds and is kept.

## Findings (2026-09-27, iteration 2)

Where each check ran: the build and both suites on the Linux box; the documentation gate on the Mac. The wait
for kafkakn was a watcher that read GitHub only (`gh api`, no local checkout). It fired when B-76 read `done` on
kafkakn's `main` and a successful `publish` run had been built from a commit containing it.

- **AC: the published version is after kafkakn B-74, and mostik compiles `enqueue`, `Delivery.await()` and
  `RecordNotQueuedException` on both builds.** Pinned `kafkakn = "0.1.0.11"`. That run's log names `0.1.0.11`,
  and it was built from kafkakn `84008f4`, the commit that closed B-76. Gradle resolved `0.1.0.11` fresh on the
  Linux box (a number, not a cached snapshot). `EnqueueContractTest` calls all three.
- **AC added by the decision: the test that found the gap is kept and green on both builds.**
  `EnqueueContractTest` passes on `jvm` and `linuxX64`: `enqueue` refuses after at least `max.block.ms`, and
  `close` then takes under 5 s. 20 tests on each build, none failed.
- **Positive control: the test tells the versions apart.** Pinned to `0.1.0.10` (after B-74, before B-76), the
  native run failed with *"Expected an exception of … RecordNotQueuedException to be thrown, but was
  kotlinx.coroutines…"*, and the JVM run passed. Back to `0.1.0.11`, both pass.
- **A reporting quirk:** the native test-result XML gave this test `time="0.003"`, although the test asserts
  that the refusal took at least 1 000 ms. The native XML's time is not a measurement. The assertion and the
  control are the evidence.
