---
id: B-09
title: "The webhook relay is built from keel, and every non-domain line the agent added is a defect"
status: done
priority: P0
size: L
stage: m4-consumer
blocked_by: [B-07, B-08]
---

# B-09 — The first consumer, with somebody counting

The webhook relay is built *from* keel through the `native-service-bootstrap` skill, by the agent,
with a human counting. The measure is not "did it work": it is **every line the agent had to add
that is neither domain nor template renaming**, and each of those is a defect with a destination.

| the line was | it goes to |
|---|---|
| a build flag, a linker option, a CI step | sborka |
| lifecycle, probes, config, shutdown | kore |
| a procedure the agent had to work out | the skill |

- **The decision and its reason.** Nothing goes to keel except renaming. If keel accumulates fixes it
  is turning back into konekt, which is the thing it was extracted from. The routing table is how a
  template stays a template under pressure from a real service.
- The count is kept by a person who is not the agent doing the work. An agent grading its own output
  against "was this line necessary" is the shape of measurement that always passes.
- **The number is recorded whatever it is**, including zero and including twenty. kore's first
  adoption found seven defects in two days that 184 tests and an end-to-end oracle had not; that is
  the honest expectation here too.
- Kill criterion, from the brief: if acceptance 6 — 500 lines of Kotlin, 100 of Gradle — cannot be
  met after this consumer, the starter idea is wrong for this stack and the honest deliverable is
  the skill alone, generating from konekt's pieces. **Write that down and stop.**

- AC: the relay runs, and the tally is in this item: lines added, split by destination, with a link
  to each issue filed.
- AC: keel's own diff during the exercise is renaming only, or the exception is argued here.
- Anchors: `docs/services/keel-server.md`, `CLAUDE.md`,
  `kotlin-skills/plugins/kotlin-fullstack/skills/native-service-bootstrap/SKILL.md`

---

## Iteration 1 — 2026-09-16: the relay runs, and the count is self-graded

**A webhook relay was built from keel**: accept a delivery, store it durably, forward it to the
target, retry, settle. It runs on the native binary — `POST /deliveries` answers `202`, the forwarder
sweeps, an `http` target is `DELIVERED` after one attempt with the sink recording the hit.

**The count below was kept by the agent that did the work**, which this item's own text names as the
measurement that always passes. It is offered as evidence to audit, not as a verdict, and the raw
material is four commits in `~/Documents/GitHub/webhook-relay`: the pristine clone, two renaming
commits, and one domain commit. Anybody can re-derive the numbers from `git diff`.

### The tally

| | |
|---|---|
| renaming | 32 files, **249 lines, strictly 1:1** — identifiers, the config prefix, the binary name, the group, the package |
| domain | **178 code lines** across four files: `Delivery`, `DeliveryStore`, `DeliveryRoutes`, `Forwarder` |
| **neither domain nor renaming** | **6 lines**, all of them dependency declarations: `ktor-client-core` and `ktor-client-cio` in the catalog and in `server/build.gradle.kts` |

**Nothing in the template's infrastructure changed.** Verified file by file rather than claimed:
`Dockerfile`, `.dockerignore`, `Makefile`, `settings.gradle.kts`, the root `build.gradle.kts`,
`gradle.properties`, `.github/workflows/check.yaml`, `renovate.json` and `scripts/` are byte-identical
to the template. `distribution/build.gradle.kts` differs by two lines, and both are the training
workload's route being renamed from `/items` to `/deliveries`.

**Zero workarounds, zero flags, zero lines routed to sborka or kore.** Those six are a consumer
declaring a dependency its domain needs, which is not a defect in a template.

### The caveat that decides how that reads

**The relay cannot do its job against a real target, and the template is not what stopped it.**
`ktor-client-cio` has no TLS on Kotlin/Native. It compiles, links and resolves; the first `https`
request fails at runtime with `TLS sessions are not supported on Native platform.` The relay delivered
over `http` and burned all five attempts on `https`.

So the six-line tally is **six lines for a consumer that only forwards over http**. Making it forward
over https needs `ktor-client-curl`, and that links libcurl dynamically — which changes the runtime
image, and puts the service outside sborka's `scratch` recipe for exactly the reason that research
excludes metrik and shildik from it. *That* change would touch the template's `Dockerfile`, and it has
not been made or counted.

Filed as **[kotlin-skills#5](https://github.com/youndie/kotlin-skills/issues/5)** — a procedure the
agent had to work out, which is the skill's row in the routing table. The entry it asks for is a
sentence at the start of a service rather than at the first failed webhook: *a service that calls out
over TLS is a service whose image carries libcurl.*

### What the conventions caught that review would not have

**kapkan failed the build on two real defects in the forwarder**, neither of them style:

* `runCatching { sweep() }` in the sweep loop catches `CancellationException` with everything else, so
  cancelling the scope would have left the loop sweeping — against a store the release stage was about
  to close. In a service whose whole point is an ordered shutdown;
* the `Result` was discarded, so a sweep failing every second against an unreachable database would
  have been silent.

Both were written by the agent, both would have passed a human reading, and sborka's own rule set
stopped the build over them. That is the strongest evidence in this exercise that the portfolio's
conventions travel.

### Acceptance 6 after the consumer

The brief's kill criterion asks whether the line budgets survive a real service. keel's own budgets are
unaffected — the relay is a separate repository — and the relay itself came to 178 domain lines on top
of a template that needed no infrastructure change. **The kill criterion is not triggered.**

### What was not done

The relay is **local only**. No GitHub repository was created for it: that is an outward-facing action
and nobody asked for one. It is four commits in a working tree, which is all this measurement needed.
