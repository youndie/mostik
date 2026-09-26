---
id: B-12
title: "An eval suite for native-service-bootstrap with checkable expectations"
status: done
priority: P2
size: M
stage: m4-consumer
blocked_by: [B-11]
---

# B-12 — "Create service X", and something that can say whether it worked

The brief asks for `evals/evals.json` beside the skill, with "create service X" and checkable
expectations. There is none: the plugin directory holds `skills/` and nothing else
([research-architecture](../research/research-architecture.md) §1.11).

- **The decision and its reason.** An expectation is checkable or it is decoration. "The service
  starts" is not checkable by a grader; "`/health/ready` answers `200` and `/health/startup` answered
  `503` before it" is. The suite is written against facts of the same kind as the BDD scenarios in
  [feature-item-round-trip](../features/feature-item-round-trip.md) §5.
- The first cases come from [B-09](B-09-first-consumer.md)'s tally: whatever the agent had to work
  out for itself is what the eval should catch next time. Writing cases before that run would be
  guessing at which steps are hard.
- The rejected alternative is a smoke test that builds the service and checks the exit code. It
  passes when the agent produces a service with one probe answering `200` for all three questions,
  which is the exact failure kore exists to stop.
- Not covered: evals for the other nine skills.

- AC: `evals/evals.json` exists beside the skill, each case naming an observable — a route, a status,
  a file that must exist, a line that must not.
- AC: at least three cases are drawn from defects the first consumer actually hit.
- Anchors: `kotlin-skills/plugins/kotlin-fullstack/skills/native-service-bootstrap/SKILL.md`,
  `docs/features/feature-item-round-trip.md`

---

## Iteration 1 — 2026-09-16: written, waiting on a repository this loop does not merge in

[kotlin-skills#7](https://github.com/youndie/kotlin-skills/pull/7) adds
`skills/native-service-bootstrap/evals/` — five cases in the shape the three sibling suites already
use, plus a fixture script and a README saying where each case came from.

| AC | |
|---|---|
| `evals/evals.json` exists, each case naming an observable | done — a route, a status, an ordering in a file, a line that must not appear |
| at least three cases drawn from defects the first consumer actually hit | done — three of the five |

The three, all from [B-09](B-09-first-consumer.md):

* **`outbound-tls-decides-the-image`** — `ktor-client-cio` compiled, linked and resolved, and the
  first `https` request failed at runtime. The case checks the agent says so *before* depending on it,
  not as a caveat afterwards;
* **`cancellation-is-not-swallowed`** — the relay's forwarder had `runCatching { sweep() }` in its
  loop, and kapkan failed the build over it. Written by an agent, passed by a human reading;
* **`nothing-closes-in-applicationstopping`** — the divergence kore exists for.

### Two judgement calls that are the reviewer's to overturn

**Several expectations check that a *reason* appears, not only that the code is right.** For a skill
whose stated value is "gotchas already paid for", an agent that writes the right dependency without
knowing why writes the wrong one when the context shifts. It is also the kind of expectation a grader
scores loosely, and someone may prefer artefacts only.

**The fixtures are deliberately not keel.** Handing the agent a finished service would let it copy an
answer rather than reach one, and every case here is about a decision.

### Not run, and not merged

The suite has not been executed. `claude plugin eval` loads the plugin and runs it on this machine as
the user — a different kind of action from writing the cases, and one nobody asked for. And the loop
does not merge in kotlin-skills: keel's `CLAUDE.md` authorises it *here*, and that authorisation does
not travel.

The item closes when a person merges the pull request. **Until then, saying "the eval suite exists"
would be the thing this whole backlog is written against.**

---

## Closed 2026-09-16

[kotlin-skills#7](https://github.com/youndie/kotlin-skills/pull/7) merged as `73f1cd2`. Verified in the
file on `origin/main` rather than in the label: five cases —
`outbound-tls-decides-the-image`, `cancellation-is-not-swallowed`,
`native-service-block-before-the-targets`, `health-is-not-readiness`,
`nothing-closes-in-applicationstopping` — beside the fixture script and the README.

**The suite has not been run, by instruction.** So what exists is a set of cases nobody has scored,
and that is the honest description of it: `evals.json` is a claim about what the skill should get
right, not evidence that it does. The first run will say which of the five the skill actually passes,
and it may well say that some expectation is scored loosely or that a fixture is too thin — the two
judgement calls flagged in the pull request are the likeliest candidates.

**That is the last item in this backlog.** What remains open is in other people's repositories
([sborka#80](https://github.com/youndie/sborka/issues/80) for the two-target staging,
[zavarnik#13](https://github.com/youndie/zavarnik/issues/13) for the training environment) and one
thing here that is deliberately not automated: `:server:measure --stand` still refuses, because the
measurement that exists was taken by hand over ssh and writing orchestration nobody has run would
give this repository the appearance of a capability it does not have.
