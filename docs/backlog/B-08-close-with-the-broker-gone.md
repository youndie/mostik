---
id: B-08
title: "How long close takes with the broker gone, measured against the grace period"
status: done
priority: P1
size: S
stage: stage-4-shutdown
epic: feature-shutdown-without-loss
blocked_by: [B-05]
---

# B-08 — how long `close` takes with the broker gone, measured against the grace period

kafkakn's `close` flushes and states no bound. With the broker gone, the native arm may wait out
`message.timeout.ms` (300 000 ms by default), which is far beyond a 30 s grace period (research §1.5,
H2). Feature: [feature-shutdown-without-loss](../features/feature-shutdown-without-loss.md).

- **The decision and its reason.** Measure it before bounding it. If `close` overruns, the process is
  `SIGKILL`ed with records still in the producer. Every one of them belongs to a request already
  answered `504`, so no answer becomes false. What changes is how the process ends, and whether the
  transcript says so.
- If it overruns on either build, a bound on `close` is filed in kafkakn. The rejected alternative is
  mostik shortening `message.timeout.ms` on its own: that is a platform key, and it would change what
  `504` means on one build only.

- AC: time from `SIGTERM` to exit, with the broker stopped and requests in flight, on both builds, at
  least three runs each, written into this item.
- AC: if either build exceeds the grace period, a kafkakn item exists and is linked here.
- Anchors: `ci/b-08/run.sh`, `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt`.

## Findings (2026-09-27)

Measured on the Linux box with `ci/b-08/run.sh native|jvm`, three rounds per build. Each round: the broker up; one
publish so that the topic's metadata is known; `docker stop` on the broker (refusing connections, unlike B-05's
pause); five publishes; 4 s so that every one has its answer past the 3 s deadline; `SIGTERM`; the broker back up;
then the five keys read out of the topic.

| | native | JVM |
|---|---|---|
| the five publishes | 5 × `504` (queued) | 5 × `429` (not queued) |
| `SIGTERM` to exit | 8 060, 8 065, 8 093 ms | 5 109, 5 046, 5 123 ms |
| exit code | 0 | 143 |
| kore's release of the producer | `RELEASE_POOLS DEADLINE_EXCEEDED in 3.0s` | `RELEASE_POOLS COMPLETED` in 7–8 ms |
| of the five records, in the topic afterwards | 0 | 0 |

- **AC: the time is measured on both builds, at least three runs each.** Neither build comes near the 30 s grace
  period. **So no kafkakn item.** B-04 saw `close` alone take 300 s, but the process does not wait for it: kore
  cancels a release participant at `releaseGroup` (3 s) and exits without it. Research H2 is refuted on these
  grounds, not on kafkakn's.
- **What the cut costs.** On native, the five queued records are dropped with the process, not flushed. Each had
  been answered `504`, so no answer is false. kore's transcript names the cut, so it is not silent.
- **Found: with the broker stopped, the builds answer differently.** The JVM build refuses (`429`); the native build
  queues (`504`). Both are true. B-05's "same status on both builds" was measured for a paused broker and a full
  queue, and this case lies outside both. It is recorded in research §3 (H2) and in the publish feature's quirks.
  Whether it goes to kafkakn is the owner's call, as B-76 was.
- **"Requests in flight" means records in the producer here.** kore's 5 s announce outlasts the 3 s deadline, so
  every request has its answer before the drain begins. What the shutdown can meet is records, not open
  requests. B-09 runs the other case, under load.
