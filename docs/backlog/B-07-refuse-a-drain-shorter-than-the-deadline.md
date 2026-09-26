---
id: B-07
title: "Refuse to start when the drain budget is shorter than the publish deadline"
status: open
priority: P1
size: S
stage: stage-4-shutdown
blocked_by: [B-05]
---

# B-07 — refuse to start when the drain budget is shorter than the publish deadline

A request in flight when the drain starts needs up to `PUBLISH_DEADLINE_MS` to get a real answer. A
drain shorter than that cuts it, and the client sees a reset connection, which is worse than a `504`
(research §1.6, D6). Feature: shutdown without loss.

- **The decision and its reason.** At start-up, mostik checks that `drain ≥ PUBLISH_DEADLINE_MS + margin`.
  If the check fails, it exits `1` with a message that names both values, the same way kore refuses a
  missing variable. The check runs at start-up rather than at shutdown, because at shutdown it would
  be too late to matter.
- Not covered: the proxy's upstream timeout. mostik cannot read it (research, risk 1).

- AC: with a drain of 3 s and a deadline of 5 s, the service exits `1` on both builds, and the message
  names `PUBLISH_DEADLINE_MS` and the drain.
- AC: with kore's defaults (a drain of 15 s) and a deadline of 5 s, the service starts.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/keel/KeelConfig.kt` (to be renamed by B-01).
