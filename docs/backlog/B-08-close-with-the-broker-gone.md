---
id: B-08
title: "How long close takes with the broker gone, measured against the grace period"
status: open
priority: P1
size: S
stage: stage-4-shutdown
blocked_by: [B-05]
---

# B-08 — how long `close` takes with the broker gone, measured against the grace period

kafkakn's `close` flushes and states no bound. With the broker gone, the native arm may wait out
`message.timeout.ms` (300 000 ms by default), which is far beyond a 30 s grace period (research §1.5,
H2). Feature: shutdown without loss.

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
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/keel/Wiring.kt` (to be renamed by B-01).
