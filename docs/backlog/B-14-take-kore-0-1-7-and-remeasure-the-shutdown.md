---
id: B-14
title: "Take kore 0.1.7: the refusal opens at the drain, and the shutdown is measured again"
status: done
priority: P1
size: M
stage: stage-5-kore-upstream
epic: feature-shutdown-without-loss
blocked_by: [B-13]
---

# B-14 — take kore 0.1.7: the refusal opens at the drain, and the shutdown is measured again

kore `0.1.7` (kore `53fe567`, #95; its publish run succeeded on 2026-09-27) changes what a client sees at
`SIGTERM`. Until now the refusal was gated on readiness, which the announce flips. So mostik answered `503` to
every request from the first millisecond of the 5 s announce, and B-09, B-11 and B-12 measured exactly that. kore
now gates the refusal on a `DrainGate` that `EngineDrain` opens as its first act. During the announce the service
**goes on serving** while `/health/ready` says `503`, which is what the announce is for: the proxy stops sending,
and what it already sent is answered. The predicate overload of `installShutdownRefusal` and the three-argument
`EngineDrain` are deprecated, so with `-Werror` the bump does not compile until the wiring moves. Feature:
[feature-shutdown-without-loss](../features/feature-shutdown-without-loss.md).

- **The decision and its reason.** Take `0.1.7` and wire the `DrainGate` as kore's sample does. Then measure again
  everything that described the announce as a window of `503`s, because every such sentence becomes false:
  - B-09: the oracle's counts (fewer `503`, more `200`) and its verdict, which must still be zero disagreements;
  - B-11: whether the resets still sit at the listener's close, now that requests are served up to the drain;
  - B-12: `ci/b-12/run.sh` expects `503` on both readiness and a publish during the announce. It has to expect
    readiness `503` and a publish `200`, then refusal from the drain on.
- The documents are corrected where each sentence stands (research, the feature, the service quirks, B-12's and
  B-11's findings by an amendment, not a rewrite), because the old behaviour was measured and stays true of
  `0.1.6`.

- AC: kore `0.1.7` pinned, the `DrainGate` wired, and no deprecated kore call left. The build is green.
- AC: `ci/b-12/run.sh`, updated, passes on both builds: through the announce readiness is `503` and a publish is
  `200`; from the drain, `503` or refused.
- AC: `ci/b-09/run.sh` gives zero disagreements in 20 rounds per build and moves its counts under the control. The
  reset count is reported against B-11's.
- AC: no document still says the announce answers `503` to a publish, unless it names `0.1.6` or older as where
  that was true.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt`, `ci/b-12/run.sh`, `ci/b-09/run.sh`.

## Findings (2026-09-27)

Everything ran on the Linux box; the gate ran on the Mac.

- **AC: kore `0.1.7`, the `DrainGate` wired, and no deprecated call.** One `DrainGate` goes to
  `installShutdownRefusal(draining)` and to `EngineDrain(…, draining)`. `mostikModule` takes it. The build is green
  with `-Werror`, and 28 tests per build pass.
- **AC: `ci/b-12/run.sh`, updated, passes on both builds.** Through the announce, readiness is `503` and a publish is
  `200` (20 probes each). The connection is refused from about 5.0 s, and a publish never got `503`, because the
  window between the drain opening and the listener closing is shorter than the probe's period.
  - **Positive control:** the updated script run against the kore `0.1.6` build fails on both. There, a publish was
    `503` from 2 ms.
  - **One correction to the script:** the first probe, 2 ms after `SIGTERM` on native, can still see readiness `200`,
    because the signal is handled off the handler. Readiness is judged from 100 ms on; a publish, throughout.
- **AC: `ci/b-09/run.sh`, 20 rounds per build: zero disagreements.** Native had 4 400 to 11 100 × `200` and 0 to 51 ×
  `503` per round; JVM 3 700 to 16 800 × `200` and 0 to 73 × `503`. Under kore `0.1.6` it was about 7 000 × `503`,
  because the announce refused.
  - **The control moved:** native 192 × `504` per round, all absent from the topic (three per client, because the
    announce now serves). JVM `504`s and `429`s, the builds' known difference with the broker stopped (B-08).
  - **Resets against B-11's:** 59 in 40 rounds (30 native, 29 JVM). 58 were within 122 ms of the listener's close,
    none with a record, which is B-11's mechanism. **One was not**, and its record was written: filed as
    [B-15](B-15-an-empty-reply-for-a-written-record.md).
- **AC: the documents.** No sentence outside the backlog still says the announce answers `503` to a publish. The
  feature, both API documents, research §1.6, `CLAUDE.md` and `backlog.md` are corrected where the sentence stood.
  B-09, B-11 and B-12 got dated amendments, not rewrites, because they measured `0.1.6` correctly.
