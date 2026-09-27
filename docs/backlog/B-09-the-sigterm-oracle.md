---
id: B-09
title: "The SIGTERM oracle: every answer a client got agrees with the topic"
status: wip
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
- Anchors: `ci/b-09/run.sh` (new).
