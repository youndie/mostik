---
id: B-09
title: "The SIGTERM oracle: every answer a client got agrees with the topic"
status: done
priority: P1
size: M
stage: stage-4-shutdown
epic: feature-shutdown-without-loss
blocked_by: [B-07, B-08]
---

# B-09 — the `SIGTERM` oracle: every answer a client got agrees with the topic

kafkakn measured the drain-then-close order through a service that stored every event before publishing
(research §1.5). mostik stores nothing, so its oracle is the client's own ledger. Feature: [feature-shutdown-without-loss](../features/feature-shutdown-without-loss.md).

- **The decision and its reason.** Run 64 concurrent publishers, each writing records with unique keys
  and keeping a ledger of the status it got for each key. Send `SIGTERM` at a random moment, 20 rounds on
  each build. Then read the topic by key:
  - every `200` is present;
  - no `429` and no `503` is present;
  - every `504` is counted in both directions: present and absent.
- **Positive control:** the broker stopped before the signal. The ledger must move: `504`s appear, and
  the check still holds.
- Rejected: counting on mostik's side. A service asked whether it answered truthfully answers yes.

- AC: 20 rounds per build with zero disagreements between the ledgers and the topic. The control moves
  the counts. Every round's exit ends the process itself, not a `SIGKILL`.
- Anchors: `ci/b-09/run.sh`.

## Findings (2026-09-27)

Run on the Linux box with `ci/b-09/run.sh`: 20 rounds per build, then 3 control rounds per build. Each round had a
topic of its own, 64 clients, and `SIGTERM` at a random 3 to 8 s. The deadline was 3 000 ms, the queue wait
1 000 ms, and the drain kore's default.

- **AC: 20 rounds per build with zero disagreements between the ledgers and the topic.** Across 40 rounds and
  about 290 000 answers:
  - no `200` is missing from its topic;
  - no `429` and no `503` is present in it.

  Per round, native: about 5 200 to 11 200 × `200`, then about 7 300 to 8 000 × `503` (kore's refusal through the
  announce), then 64 refused connections, one per client. JVM: about 3 700 to 9 800 × `200`, then almost no `503`
  (see B-12), then refused.
- **AC: the control moves the counts.** With the broker stopped 1 s before the signal, every control round got
  64 × `504`, one per client in flight. On native all 64 were absent from the topic in each round. On the JVM,
  62 were absent and **2 present** in round 1, and 64 absent in the other two. "Unknown" was true both ways, which
  is why `504` is not `503`.
- **AC: every exit is the process's own.** Exit 0 on every native round, and 143 on every JVM round, the
  platform difference `CLAUDE.md` names. No `SIGKILL`.
- **Found: reset connections, 1 to 9 per round,** in 10 of 20 native rounds and 15 of 20 JVM rounds (curl 56,
  twice 52). Checked key by key: **none of those records is in the topic**. The feature says "never a reset
  connection", so the rule is broken, although no answer is false. Filed as
  [B-11](B-11-a-connection-reset-at-shutdown.md), with a hypothesis about the mechanism.
  - The script's verdict of this run counted resets as failures ("10 of 20", "15 of 20"). The script now reports
    them beside the verdict. This paragraph and the per-round lines are what the run said.
- **Found: the JVM build stops listening at `SIGTERM`.** Probed every ~220 ms: native answers `503` for 4.9 s, and
  the JVM build refuses connections from 1 ms on. Filed as
  [B-12](B-12-the-jvm-build-stops-listening-at-sigterm.md). It is likely keel's or kore's, not mostik's.

## Amended (2026-09-27, B-14)

The counts above are kore `0.1.6`'s, where the announce answered `503`. Under `0.1.7` the announce serves, and the
same 20 rounds per build gave: native 4 400 to 11 100 × `200` and 0 to 51 × `503` per round; JVM 3 700 to 16 800 ×
`200` and 0 to 73 × `503`. There were zero disagreements again. The control moved: 192 × `504` per native round,
and `504`s with `429`s on the JVM.
