---
id: B-19
title: "Assert the JVM half's shutdown, once the oracle can reach a distribution"
status: done
priority: P2
size: S
stage: m1-ships-twice
epic: feature-item-round-trip
---

# B-19 — The half the oracle cannot see

B-07 closed on the native image, which is what deploys. The JVM distribution's shutdown is **observed**
correct — exit `143`, kore's full transcript, in B-03's smoke — and asserted by nothing.

- **Why this is not a formality.** kore exists because `EmbeddedServer.stop` runs its steps in the
  **opposite order** on JVM and Kotlin/Native, from identical source. The JVM half is precisely where
  a shutdown defect can live that a green native run cannot see. keel registers nothing in
  `ApplicationStopping`, which is the wiring that trips over it, so there is reason to expect it is
  fine — and reason to expect is not a check.
- **Blocked on [kore#85](https://github.com/youndie/kore/issues/85)**, which asks whether the oracle
  should drive a distribution as well as an image. keel deliberately ships no JVM image: a test-only
  artefact in a template is inherited by every clone.
- **If kore says no**, this item closes as `dropped` rather than lingering, and B-07's iteration 3
  becomes the record of what keel chose not to cover. That is a legitimate outcome, not a failure.
- The rejected alternative is keel containerising its distribution for the test. It is the option that
  needs nobody's permission and it is the one that costs a clone the most.

- AC: the oracle runs against the JVM distribution with a non-zero in-flight count, and the findings
  match the native run's — or the difference is explained.
- AC: the feature's `kill -TERM` scenario loses its "not yet run against the JVM half" qualifier.
- Anchors: `distribution/build.gradle.kts`, `docs/backlog/B-07-shutdown-oracle.md`

---

## Iteration 1 — 2026-09-16, done: both halves, seven passes each, nothing failed

[kore#85](https://github.com/youndie/kore/issues/85) landed — the oracle takes `--command` and drives a
local process, so a distribution is a subject. Verified in kore's source at `9a7baac` before running
anything.

**The JVM distribution:**

```
PID 1: .../distribution/build/install/distribution/bin/distribution
exchanges: 289, spanning the signal: 32

  PASS  A1 in-flight requests finished — 32 spanned the signal, all completed
  PASS  A2 no 500        PASS  A3 503 carries Connection: close — 32 refusals, all carrying it
  PASS  A6 exited itself inside the grace period — exit 143 after 15 028ms of 30 000ms
result: 7 passed, 0 failed, 0 inconclusive, 2 not applicable
```

**The native image, re-run on the same oracle:** identical verdicts — 4 372 exchanges, 32 of 32
spanning the signal all completed, 4 085 refusals every one carrying `Connection: close`, **exit 0**.

**So the asymmetry this item was written about is closed.** kore exists because
`EmbeddedServer.stop` runs its steps in the *opposite order* on the two platforms, which made the JVM
half the place a shutdown defect could hide from a green native run. Both halves now assert the same
seven things from the client's record, and the only difference between them is the exit code — `143`
and `0`, both correct, which is why nothing asserts a specific one.

### A4 went from FAIL to NOT_APPLICABLE, and that is the right answer

[kore#83](https://github.com/youndie/kore/issues/83) is fixed in the shape that was proposed: the
assertion now brackets the fall rather than comparing against a sample, and where the bracket cannot
place the two events it says so.

> `NOT_APPLICABLE  A4 — the first refusal landed inside the 103ms between the last 200 and the first
> non-200 — this run cannot place the two, which a route that answers in about a millisecond will do
> every time`

**A run that cannot answer a question now says it cannot**, instead of failing keel for a measurement
limit. That is the same distinction `INCONCLUSIVE` already carried for the vacuity guards, applied to
precision — and it means keel's refusal to wire the two flags apart, which would have made a broken
A4 pass, was the right call.
