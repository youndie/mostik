---
id: B-12
title: "The JVM build stops listening at SIGTERM, before kore's announce"
status: done
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
- Anchors: `server/src/jvmMain/kotlin/io/github/youndie/mostik/Main.kt`, `distribution/src/main/kotlin/io/github/youndie/mostik/jvm/Main.kt`, `ci/b-12/run.sh`.

## Findings (2026-09-27)

- **AC: the cause, read out of the artefacts.**
  `ktor-server-core-jvm-3.6.0-sources.jar!/jvmMain/io/ktor/server/engine/EmbeddedServerJvm.kt`: `start(wait)` calls
  `addShutdownHook { stop() }` unconditionally. On the JVM that is a `Runtime` shutdown-hook thread
  (`ktor-server-core-jvm-3.6.0-sources.jar!/jvmMain/io/ktor/server/engine/ShutdownHookJvm.kt`), and the JVM runs all
  hooks concurrently. So `stop()` ran while kore's sequence announced. The same file reads the system property
  `io.ktor.server.engine.ShutdownHook` into `SHUTDOWN_HOOK_ENABLED`. kore's research §1.3 has both facts, but its
  consequence 3 ("kore has to be the thing that is later") reasons about Native's single slot only.
- **AC: reproduced on keel's own JVM distribution** (`youndie/keel@966f481`, built on the Linux box): connection
  refused from 2 ms after `SIGTERM` for the whole announce, while the transcript said `ANNOUNCE COMPLETED in 5.0s`.
  So it is keel's and kore's, filed as **youndie/kore#90** before this merge. kore owns the signal and stops the
  engine itself, so kore is the place to switch Ktor's hook off.
- **AC: after the workaround, the JVM build answers `503` through the announce.** `ci/b-12/run.sh`:

  | | 503 probes | first refused connection |
  |---|---|---|
  | native | 22 | 5 097 ms |
  | JVM, with `keepKtorOutOfTheShutdown()` | 22 | 5 071 ms |
  | JVM, the call commented out (control) | 0 | **3 ms**, and the script says `FAIL` |

- **The workaround** sets the property as the first line of both JVM entry points, before the server exists.
  Ktor reads it once, when `ShutdownHookJvm` loads at `start()`. It carries the issue's address, to be deleted
  when kore does it.
- **Not measured here:** whether this also removes the JVM's share of B-11's reset connections. The JVM rounds had
  more resets than native, and an engine stopped mid-request is one way to get them. That is for B-11 to measure.
