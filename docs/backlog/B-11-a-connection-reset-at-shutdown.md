---
id: B-11
title: "A few requests at shutdown get a reset connection instead of an answer"
status: done
priority: P2
size: S
stage: stage-4-shutdown
epic: feature-shutdown-without-loss
---

# B-11 — a few requests at shutdown get a reset connection instead of an answer

B-09's oracle, 20 rounds per build with 64 clients, found reset connections at `SIGTERM`: curl exit 56 (a few
52), 1 to 9 per round, in 10 of 20 native rounds and 15 of 20 JVM rounds. The feature promises "never a reset
connection". Not one of those requests' records reached the topic (checked key by key against each round's
topic), so no reset hid a written record. The client still got no answer. Feature:
[feature-shutdown-without-loss](../features/feature-shutdown-without-loss.md).

- **Hypothesis, not yet measured.** These are connections the kernel had already accepted into the listening
  socket's backlog when the engine closed the socket. Closing a listener with a non-empty backlog resets those
  connections, and no application code ever saw them. If that is the mechanism, the fix is to stop *accepting*
  before the drain rather than let the drain close a socket with a backlog. That is kore's `EngineDrain` or
  Ktor's CIO, not mostik.
- **The decision and its reason.** Establish the mechanism before changing anything: for each reset, when the
  connection was opened relative to the listener's close. A fix to the wrong layer would move the resets rather
  than remove them.
- Not covered: the JVM build's early close of its listener, which is B-12 and may be the larger share on the JVM.

- AC: the mechanism is measured and written into this item, with the layer it belongs to.
- AC: either the resets are gone under `ci/b-09/run.sh` (reset 0 in 20 rounds per build), or an issue exists in
  the layer that owns them, and the feature document says what a client can meet.
- Anchors: `ci/b-09/run.sh`.

## Findings (2026-09-27)

`ci/b-09/run.sh`'s ledger now carries each request's start and end time, and each round records when the signal
was sent. That is how the mechanism was read. It ran 5 rounds per build on the Linux box, after B-12.

- **AC: the mechanism, measured.** Every reset, relative to the signal:

  | round | first refused connection | last `503` | resets (start → end) |
  |---|---|---|---|
  | JVM r1 | +5 033 ms | +5 037 ms | +5 016 → +5 032, +5 015 → +5 035 |
  | JVM r5 | +5 019 ms | +5 028 ms | +4 990 → +5 025, +5 004 → +5 020 |
  | native r3 | +5 025 ms | +5 026 ms | +5 008 → +5 024 |

  Every reset started 4 to 43 ms before the listening socket closed, at the end of kore's 5 s announce where
  `EngineDrain` stops the engine. Each ended within 9 ms of that close. The hypothesis holds: these connections
  were in the listener's accept queue when it closed, and the kernel resets such a queue with its socket. Each fell
  in the window where the service was answering `503`. None carried a record (B-09 checked key by key), so the only
  thing lost is the form of a `503`.
- **AC: gone, or an issue in the layer that owns them, and the feature says what a client can meet.** No layer
  can remove it. Any listening socket that closes races with the connections still arriving, and clients that
  ignore readiness keep arriving. kore's announce already answers `/health/ready` with `503` for 5 s, and a proxy
  that follows it stops sending before the close. So no issue is filed, and the feature document and the service
  quirks now state it as what a client can meet, not as a promise of "never".
- **B-12 took most of them away.** B-09's JVM rounds had resets in 15 of 20. After B-12, 2 of 5 JVM rounds and 1 of
  5 native rounds had any, 5 resets in 10 rounds. The JVM's extra share was its engine stopping mid-request at the
  signal.
- **No disagreement in these 10 rounds either:** every `200` present, no `429` or `503` present, every exit the
  process's own.
