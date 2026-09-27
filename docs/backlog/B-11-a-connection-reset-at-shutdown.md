---
id: B-11
title: "A few requests at shutdown get a reset connection instead of an answer"
status: wip
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
- Anchors: `ci/b-09/run.sh`, `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt`.
