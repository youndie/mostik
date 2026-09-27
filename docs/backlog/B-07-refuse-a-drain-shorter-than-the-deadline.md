---
id: B-07
title: "Refuse to start when the drain budget is shorter than the publish deadline"
status: done
priority: P1
size: S
stage: stage-4-shutdown
epic: feature-shutdown-without-loss
blocked_by: [B-05]
---

# B-07 — refuse to start when the drain budget is shorter than the publish deadline

A request in flight when the drain starts needs up to `PUBLISH_DEADLINE_MS` to get a real answer. A
drain shorter than that cuts it, and the client sees a reset connection, which is worse than a `504`
(research §1.6, D6). Feature: [feature-shutdown-without-loss](../features/feature-shutdown-without-loss.md).

- **The decision and its reason.** At start-up, mostik checks that `drain ≥ PUBLISH_DEADLINE_MS + margin`.
  If the check fails, it exits `1` with a message that names both values, the same way kore refuses a
  missing variable. The check runs at start-up rather than at shutdown, because at shutdown it would
  be too late to matter.
- Not covered: the proxy's upstream timeout. mostik cannot read it (research, risk 1).

- AC: with a drain of 3 s and a deadline of 5 s, the service exits `1` on both builds, and the message
  names `PUBLISH_DEADLINE_MS` and the drain.
- AC: with kore's defaults (a drain of 15 s) and a deadline of 5 s, the service starts.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/mostik/MostikConfig.kt`, `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt`.

## Findings (2026-09-27)

Where each check ran: the build, both suites and the binaries on the Linux box; the documentation gate on the
Mac. There is no CI.

- **The drain was not a setting, and it became one: `MOSTIK_DRAIN_MS`.** kore takes the drain from code only
  (`ShutdownDeadlines(drain = …)`, with 15 s as its default), so "a drain of 3 s" in this item's AC could not be
  written. The grace period belongs to the deployment: `docker stop` gives 10 s, while kore's default deadlines
  add up to 29 s. So the key is needed for more than this test. The default repeats kore's. Research D6 records
  the decision and the 1 000 ms margin.
- **AC: drain 3 s with a deadline of 5 s exits 1 on both builds, naming both.**
  *"MOSTIK_DRAIN_MS (3000) must be at least MOSTIK_PUBLISH_DEADLINE_MS (5000) + 1000 ms: a request in flight at
  SIGTERM would be cut before its answer"*, on the native binary and the JVM distribution.
- **AC: kore's default drain with a deadline of 5 s starts.** Both builds are ready, with
  `drain=15000ms` in their configuration line.
- **The setting reaches kore, shown and not assumed.** With one idle TCP connection open at `SIGTERM`, CIO
  spends the whole drain: `DRAIN DEADLINE_EXCEEDED in 6.000457753s` at 6 000 ms, and in `15.000330303s` at the
  default. A configuration line printing `6000ms` would not have shown that.
- **Suites:** 28 tests on `jvm` and 28 on `linuxX64`, none failed, from fresh result files.
- **Found: a busy port aborts the native build.** A reused port made one run die with exit 134 and
  `Uncaught Kotlin exception` (`EADDRINUSE`), with no one-line refusal. Reproduced on purpose. Filed as
  [B-10](B-10-a-busy-port-is-a-refusal-not-an-abort.md), not fixed here.
- **Found in the harness:** `nc -l` waited for a connection that never came, because mostik could not bind, and
  it held the SSH session until it was killed. A `pkill -f` pattern then matched its own command line. Neither
  touched mostik.
