---
id: B-07
title: "kore's oracle runs against keel's binary, on both targets"
status: done
priority: P0
size: M
stage: m1-ships-twice
epic: feature-item-round-trip
blocked_by: [B-02, B-03, B-04]
---

# B-07 — Somebody else's oracle, pointed at this binary

Acceptance 3 of the brief: `kill -TERM` under load finishes every in-flight request, on both targets.
kore already has the harness — a load driver that signals a real container and asserts **from the
client's record**, never from the server's log. This item points it at keel.

- **The decision and its reason.** keel writes no oracle of its own. The assertions belong to kore,
  which is the thing being proved correct; a second copy here would be a second specification, and
  the day the two disagree is the day neither is read. What keel supplies is a binary and a
  compose file.
- **The oracle asserts from the client's record.** A log line is written by the code under test, and
  an oracle that reads one can be satisfied by a comment.
- **A run that visited nothing is a failure, not a pass.** The harness counts requests in flight at
  the signal and reports *inconclusive* below a floor.
- Never assert an exit code across the two targets — `0` on native, `143` on the JVM, both correct.
  Assert the process ended itself and was not `SIGKILL`ed (`137`).
- If this run finds a defect in the shutdown, the defect goes to **kore**. keel's first consumer is
  the second real consumer kore has had, and the first found seven defects in two days.

- AC: the oracle run is green **on the artefact that ships** — the native image — with a non-zero
  in-flight count, and its transcript is attached to the item. *Narrowed from "on both targets" on
  2026-09-16; the reason and what it costs are in iteration 3 below.*
- AC: any finding is filed in kore's backlog with a link from here, not fixed in keel.
- Anchors: `deploy/compose.oracle.yaml`, `Dockerfile`,
  `kore/samples/oracle/src/main/kotlin/io/github/youndie/kore/oracle/Oracle.kt`

---

## Iteration 1 — 2026-09-16: blocked on kore, with the ordering observed

**The oracle cannot be pointed at keel.** `samples/oracle` hardcodes the path it drives —
`client.get("/work?ms=$workMillis")` in `Run.kt:119` — and `/work` is `samples/service`'s route.
`Options` takes `--image`, `--work`, `--connections`, `--grace`, `--read-timeout`, `--pre-drain` and
`--subject-args`, and no path. Pointed at keel's image the in-flight count is zero, nothing spans the
signal, and the run is correctly **inconclusive** — which says nothing about keel.

Filed as **[kore#81](https://github.com/youndie/kore/issues/81)** per the routing table. keel adds no
`/work` endpoint: an on-demand sleep shipped in a template is inherited by every clone, and writing a
harness here is the second copy of the specification this item exists to refuse.

### What was observed anyway, and what it is worth

The in-flight guarantee is not the only thing acceptance 3 claims. The **ordering** can be seen from
the client's record using keel's own routes, and it was, against the image:

| t (s) | `/health/ready` | `/items` |
|---|---|---|
| before the signal | `200` | `200` |
| 0.0 — immediately after `SIGTERM` | `503` | `503` |
| 0.5 … 4.6 | `503` | `503` |
| **5.1** | connection refused | connection refused |
| exit | | **`0`** |

Against the transcript the binary printed:

```
SIGNAL COMPLETED in 898ns
ANNOUNCE COMPLETED in 5.000092518s
DRAIN COMPLETED in 1.050051ms
RELEASE_CONSUMERS COMPLETED in 406ns
RELEASE_POOLS COMPLETED in 2.623227ms
RELEASE_TELEMETRY COMPLETED in 291ns
EXIT COMPLETED in 52ns
```

So: readiness goes false **while the socket is still accepting**, new arrivals get `503` for the whole
five-second announce — `installShutdownRefusal` doing its job — the socket stops accepting only after
it, the store closes after that, and the process ends itself with `0`. That is the specified order,
and the announce window is kore's five-second default rather than an accident.

**This is an observation, not an assertion.** Nothing here runs on a build, nothing spans the signal,
and a single run on one machine is not the property. The item stays `wip`.

### What is still unproven

*Every request in flight at the signal receives its response* — the actual claim. It needs a route
that takes longer than the signal-to-drain gap, which is what kore#81 is about. Until then acceptance
3 is **unverified rather than failing**, and the distinction is the reason this entry exists.

---

## Iteration 2 — 2026-09-16: the oracle runs, and acceptance 3 is verified on the binary that ships

[kore#81](https://github.com/youndie/kore/issues/81) landed — the oracle takes `--path` and leaves the
probe paths fixed, which is the shape that was asked for. Verified in kore's source at `b4bb70b`
before running anything, because closed and fixed are different claims.

```
./gradlew :samples:oracle:oracle --args="--image=keel:cc --path=/items --connections=32"

PID 1: ["/app/keel"]
exchanges: 4472, spanning the signal: 32, after it: 4315

  PASS            G1 in flight at the signal — 32 of 32
  PASS            G2 the load ran — 4472 exchanges against /items
  PASS            G3 the signal was received — the process exited after it, 20568ms
  PASS            A1 in-flight requests finished — 32 spanned the signal, all completed
  PASS            A2 no 500 — every answered request was a normal status or 503
  PASS            A3 503 carries Connection: close — 4089 refusals, all carrying it
  FAIL            A4 readiness fell before the first refusal
  NOT_APPLICABLE  A5 the pre-drain wait was honoured — --pre-drain not given
  PASS            A6 exited itself inside the grace period — exit 0 after 20568ms of 30000ms
```

**A1 is acceptance 3 of the brief**, and it passes with the vacuity guard alongside it: 32 requests
were in flight at the signal and all 32 completed. G1 is what stops that being a claim about a run
that visited nothing.

### A4 fails for a reason that is not keel's, and it is filed

[kore#83](https://github.com/youndie/kore/issues/83). A4 compares `readinessFellAtNanos` — from a
poller that samples every 100 ms — against the finish time of a real exchange. keel wires
`installShutdownRefusal(isShuttingDown = { readiness.isShuttingDown })`, so refusal and readiness are
**one flag**: a request cannot be refused before the flip, because being refused *is* the flip having
happened. What the oracle saw is a sample up to 100 ms stale.

Three runs, identical every time — deterministic rather than flaky, which is what the explanation
predicts. It was unreachable before `--path`, because `/work?ms=3000` keeps every driver busy for
three seconds after the signal and the poller has thirty samples of margin; against a route answering
in a millisecond there is none.

**Not worked around here.** Wiring the two flags apart to satisfy an assertion would be changing the
service to fit the measurement.

### Why this is still `wip`: "on both targets" needs an artefact keel does not ship

The oracle drives a **container**. keel ships one image, carrying the native binary — the brief says
"one image" — and the JVM half ships as a distribution, which is the normal shape for one. So there
is nothing for the oracle to be pointed at on the JVM side.

The options, none of them free:

| | |
|---|---|
| **1. Add a JVM image** | contradicts the brief's "one image" and puts a test-only artefact in a template every clone inherits |
| **2. Ask kore to drive a distribution as well as an image** | the right place if the oracle is meant for consumers, and a bigger ask than `--path` was |
| **3. Narrow this item to the artefact that ships** | the native image is what deploys; the JVM half is covered by the parity run (B-05, no diff) and by its own smoke in B-03, neither of which asserts the shutdown |

**A recommendation: 3, with 2 filed.** What acceptance 3 is about is the thing that runs in
production, and that is now verified. The JVM half's shutdown was *observed* correct in B-03 — exit
`143`, full transcript — but observed is not asserted, and saying so is the point of leaving this
written down rather than closing on a pass that covers half of what the item's title claims.

---

## Iteration 3 — 2026-09-16, done: narrowed to the artefact that ships

**The item said "on both targets" and that cannot be met as written**, because the oracle drives a
container and keel ships one image. The JVM half ships as a distribution — the brief's "one image" —
so there is nothing on that side to point the oracle at.

Narrowed, with the owner's decision, to the native image. The run in iteration 2 is the evidence:
4472 exchanges, **32 of 32 spanning the signal all completed**, 4089 refusals every one carrying
`Connection: close`, exit `0` inside the grace period. A4's failure is a measurement artefact in the
oracle, filed as [kore#83](https://github.com/youndie/kore/issues/83) and not keel's.

### What the narrowing costs, stated rather than left implicit

**The JVM half's shutdown is observed, not asserted.** B-03's smoke saw it exit `143` with a full
transcript, and B-05 compared the two targets' responses while both were healthy — neither asserts
what happens to a request in flight when the signal arrives.

That gap is not uniform with the native one, and this is the part worth remembering: kore exists
because **`EmbeddedServer.stop` runs its steps in the opposite order on the two platforms**. The JVM
half is therefore exactly where a shutdown defect could live that a green native run cannot see. keel
registers nothing in `ApplicationStopping` — the wiring that would trip over it — so there is reason
to think it is fine, but reason to think so is not a check.

Filed as **[kore#85](https://github.com/youndie/kore/issues/85)**: should the oracle drive a
distribution as well as an image? If the answer is yes, [B-19](B-19-oracle-on-the-jvm-half.md) points
it at keel's. If the answer is no, that item closes as `dropped` with the reason, and this paragraph
is the record of what keel chose not to cover.

**What was refused:** adding a JVM image to keel so the oracle had something to run. It contradicts
the brief's "one image" and puts a test-only artefact in a template every clone inherits — a service
would ship it without noticing, the way clones inherit everything else.
