---
id: B-12
title: "The JVM build stops listening at SIGTERM, before kore's announce"
status: wip
priority: P1
size: S
stage: stage-4-shutdown
epic: feature-shutdown-without-loss
---

# B-12 — the JVM build stops listening at `SIGTERM`, before kore's announce

Found in B-09 on 2026-09-27. After `SIGTERM`, `/health/ready` and a publish were probed every ~220 ms on each
build. Feature: [feature-shutdown-without-loss](../features/feature-shutdown-without-loss.md).

- **native:** `503` on both, from 3 ms after the signal until 4.9 s. That is kore's refusal during the 5 s
  announce. Then the connection is refused.
- **JVM:** **connection refused** from 1 ms after the signal, for the whole announce. kore's transcript still
  says `ANNOUNCE COMPLETED in 5.0s`, but nothing is listening while it waits.

So on the JVM build the announce window does nothing a load balancer or a client can observe. The readiness probe
does not turn `503`; it stops answering. In B-09's 20 JVM rounds no request got a `503` at all: 0 in 16 rounds,
1 to 34 in the other four. The native rounds had about 7 000 to 8 000 each. No answer was false. A refused
connection is a request that was never sent.

- **Where it belongs.** The wiring is keel's and the lifecycle is kore's. kore's research names Ktor's
  `EmbeddedServerJvm.kt` among the five platform divergences a service inherits, and Ktor's JVM server installs
  a shutdown hook of its own. The likely cause, a hypothesis until read in the artefact, is that Ktor's own JVM
  shutdown hook stops the engine at `SIGTERM`, alongside kore's sequence.
- **The decision and its reason.** Read the cause out of Ktor 3.6.0's and kore 0.1.4's artefacts first. Then
  reproduce it on keel itself, which would make it keel's and kore's defect, not mostik's. File it there before
  any workaround here (`CLAUDE.md`, *Where a line goes*).

- AC: the cause is read out of the artefacts and written here, with the address.
- AC: reproduced, or not, on keel's own JVM distribution. If reproduced, the issue exists in keel or kore.
- AC: after a fix or a workaround, the JVM build answers `503` through the announce, probed as above.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt`.
