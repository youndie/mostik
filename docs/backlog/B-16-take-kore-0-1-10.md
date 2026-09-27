---
id: B-16
title: "Take kore 0.1.10: the signal is kore's from before start, and its handler is written in C"
status: done
priority: P2
size: S
stage: stage-5-kore-upstream
epic: feature-shutdown-without-loss
---

# B-16 — take kore 0.1.10

mostik pins kore `0.1.7`. Three releases since then change the path mostik's shutdown takes on the native build.
Feature: [feature-shutdown-without-loss](../features/feature-shutdown-without-loss.md).

| kore | change | kore item |
|---|---|---|
| `0.1.8` (`e27df52`, #97) | `requireListenable` defaults to `SO_REUSEADDR`, as the engine needs on native | B-62 |
| `0.1.9` (`ae4a9ad`, #98) | `startForKore()` installs kore's signal handler before `start`, again at `ApplicationStarted` and after `start` returns. Before, a `SIGTERM` between `start` and `runUntilSignal` met Ktor's native handler, which runs `stop()` on the signal stack and hung the process | B-63 |
| `0.1.10` (`76d3c66`, #100) | on Linux the handler is C, through cinterop. The Kotlin bridge it replaces initialised the runtime on whichever thread got the signal, and a newborn worker thread then died with exit 139 | B-64 |

- **The decision and its reason.** Take all three at once. mostik already calls `startForKore()`, then
  `runUntilSignal`, and passes `REUSE_ADDRESS` to the engine and the check alike. So no code has to change, and
  what this item owes is the measurement that the shutdown still behaves: the same oracles as B-13 and B-14.
- **The window B-63 closed is where mostik is most exposed:** a pod that is killed while it starts. So this item
  also signals the native build the moment `/health/ready` first answers, 100 times. The two defects were rare
  under kore's own widened conditions (B-63 needed a one-second pause to show 30 of 30; B-64 was 1 to 3 in 400
  with a 100 ms pre-drain). A clean run under mostik's defaults is therefore a smoke test, not proof of the fix,
  and the findings say so.
- Not covered: kore's own verification of the three fixes, which is in its items.

- AC: `gradle/libs.versions.toml` pins kore `0.1.10`. Both suites pass on the Linux box and in CI.
- AC: `ci/b-10/run.sh`, `ci/b-12/run.sh` and `ci/b-13/run.sh` pass on the builds they cover.
- AC: `ci/b-09/run.sh`, 5 rounds per build: zero disagreements between the ledgers and the topic.
- AC: `ci/b-16/run.sh`: 100 native starts, each signalled as soon as `/health/ready` answers. Every one exits `0`
  within its grace period, and none hangs or dies with a signal.
- Anchors: `gradle/libs.versions.toml`, `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt`.

## Findings (2026-09-27)

Everything below ran on the Linux box, on the `linuxX64` release binary and the JVM distribution built from this
branch. `distribution/build/install/distribution/lib` holds `kore-core-jvm-0.1.10.jar` and
`kore-ktor-jvm-0.1.10.jar`, so the builds did change.

- **AC: kore `0.1.10`, both suites.** `make build` is green: `jvmTest` and `linuxX64Test` each ran 28 tests with no
  failures, and the result files are from that run. No code had to change. Two comments in `Wiring.kt`, and the rule
  in `CLAUDE.md`, now say why `runUntilSignal` has to follow `startForKore` on native, and that kore's
  port check defaults to `SO_REUSEADDR` since `0.1.8`.
- **AC: `ci/b-10`, `ci/b-12`, `ci/b-13`, all PASS on both builds.**
  - b-10: a busy port is exit 1 and one line.
  - b-12: 20 announce probes with readiness `503` and a publish `200`. The connection was refused from 5 114 ms on
    native and 5 193 ms on the JVM, with no publish `503` before it.
  - b-13: a restart over 20 (native) and 40 (JVM) TIME_WAIT sockets served.
- **AC: `ci/b-09`, 5 rounds per build: 0 of 5 rounds with a disagreement on either build.** No `200` was missing
  and no `429` or `503` was present. The per-round lines were kept for rounds 3 to 5 only: 7 071 to 14 430 `200`s a
  round, and 1 to 6 resets a round, the kind B-11 explained.
- **AC: `ci/b-16`, 100 native starts signalled as `/health/ready` first answered: 100 exited `0`**, the slowest in
  5 071 ms (the announce), with no hang and no signal death.
  - The script's controls were run first and caught what they stand for. A stand-in that answers readiness and
    ignores `SIGTERM` was 2 of 2 hung; one that dies of `SIGSEGV` on it was 2 of 2 exit 139.
  - **This is a smoke test, not proof of kore's fixes.** Nothing here was run on kore `0.1.7` for comparison.
    Under mostik's defaults the windows kore B-63 and B-64 describe are small (they needed a widened window, or a
    100 ms pre-drain, to show). What this shows is that mostik's start, under the new handler, ends cleanly when
    it is signalled at once, which is the case of a pod killed while it starts.
