---
id: B-13
title: "Take kore 0.1.6 and replace both local workarounds with kore's fixes"
status: done
priority: P1
size: S
stage: stage-5-kore-upstream
epic: feature-shutdown-without-loss
---

# B-13 — take kore 0.1.6 and replace both local workarounds with kore's fixes

Two of mostik's defects were filed upstream and fixed there. Both are released in kore `0.1.6`: the publish run
built from kore `38b6248` succeeded on 2026-09-27. keel already runs on it (`youndie/keel@909866d`). mostik still
pins kore `0.1.4` and carries its own workarounds. Feature:
[feature-shutdown-without-loss](../features/feature-shutdown-without-loss.md).

| mostik's workaround | kore's fix | issue |
|---|---|---|
| `keepKtorOutOfTheShutdown()` sets `io.ktor.server.engine.ShutdownHook=false` in every JVM `main` (B-12) | `server.startForKore()` switches Ktor's JVM hook off and starts the server (kore #92) | youndie/kore#90 |
| `portProblem(port)` binds `MOSTIK_PORT` once with `ktor-network` (B-10) | `Configuration.requireListenable(port)` throws the `ConfigurationException` `mostikMain` already catches (kore #91, #93) | youndie/keel#49 |

- **The decision and its reason.** Pin kore `0.1.6`, call `startForKore()` and `requireListenable(MostikConfig.PORT)`,
  and delete both workarounds with their tests. The fixes live where the rule in `CLAUDE.md` puts lifecycle code,
  and kore's are better than mostik's:
  - **`portProblem()` has a race that kore's check does not.** On native, `ktor-network`'s `close()` does not close
    the descriptor; it queues the close for the selector thread (`TCPServerSocketNative.close` → `notifyClosed`).
    So the port can still be bound when the engine binds it. kore's CI caught exactly that as a flaky test (#93)
    and closes the socket per platform. mostik's runs never showed it, and that proves nothing.
  - **A restart over TIME_WAIT.** keel needed `e6a12eb` ("let the native build restart over its own TIME_WAIT")
    and passes `reuseAddress` to the check to match the engine. Whether mostik's native build restarts on its own
    port right after a shutdown under load is *not measured*. This item measures it.
- **Not this item:** kore `0.1.7`. It moves the shutdown refusal from the announce to the drain, which changes what
  clients see during shutdown. That is [B-14](B-14-take-kore-0-1-7-and-remeasure-the-shutdown.md), measured on its
  own.

- AC: `gradle/libs.versions.toml` pins kore `0.1.6`. `keepKtorOutOfTheShutdown` and `portProblem` are gone, with
  `PortProblemTest`, and the `ktor-network` line too if nothing else imports it. The build is green on the Linux box.
- AC: `ci/b-10/run.sh` passes on both builds: a busy port is exit 1 and one sentence naming `MOSTIK_PORT`.
- AC: `ci/b-12/run.sh` passes on both builds: the JVM build answers `503` through the announce, as under the
  workaround.
- AC: `ci/b-03/run.sh` passes on both builds, and a native restart on the same port within a second of a shutdown
  under load starts. Measured, with `reuseAddress` set as keel set it if it does not.
- Anchors: `gradle/libs.versions.toml`, `server/src/commonMain/kotlin/io/github/youndie/mostik/MostikMain.kt`,
  `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt`,
  `server/src/jvmMain/kotlin/io/github/youndie/mostik/Main.kt`.

## Findings (2026-09-27)

Everything ran on the Linux box; the gate ran on the Mac.

- **AC: kore `0.1.6` pinned, both workarounds gone.** `startForKore()` in `Wiring.kt`,
  `requireListenable(MostikConfig.PORT, reuseAddress = REUSE_ADDRESS)` in `mostikMain`. `keepKtorOutOfTheShutdown`,
  `portProblem`, `PortProblemTest` and the `ktor-network` line are removed: +11 −98 lines before the flag below.
  The build is green, with 28 tests per build and none failing. The count is two fewer than before, because
  kore's test replaces `PortProblemTest`.
- **AC: `ci/b-10/run.sh`, `ci/b-12/run.sh` and `ci/b-03/run.sh` pass on both builds**, before and after the flag.
- **AC: a native restart on the same port right after a shutdown under load, measured.** The new `ci/b-13/run.sh`
  sends 20 publishes with `Connection: close`, so there are 20 connections in TIME_WAIT, then `SIGTERM`, then the
  restart:

  | | without `SO_REUSEADDR` | with `REUSE_ADDRESS = true` |
  |---|---|---|
  | native | refused, 3 of 3: `MOSTIK_PORT: 18106 cannot be listened on: bind: Address already in use (errno 98)` | ready, 3 of 3 |
  | JVM | ready, 3 of 3 | ready |

  This is the same as keel's `e6a12eb`: native CIO applies `reuseAddress = false` literally, and the JVM's NIO sets
  it itself. The engine and kore's check now take one constant. The column without the flag is the positive
  control of the column with it.
- **The first version of `ci/b-13/run.sh` measured the wrong thing.** It started the service in `$(...)`, so `wait`
  could not reach the process, and the restart raced the old process for its port. Both builds "failed". The
  script now starts the service in its own shell, waits for the process to be gone, and checks that nothing
  listens before the restart.
- **Was the restart problem there before B-13?** Very likely: `portProblem()` bound without `SO_REUSEADDR` too, and
  CIO did as well. B-09's rounds reused one port without showing it, because curl closes its side first when the
  server does not ask for `Connection: close`. That is inferred, not measured.
