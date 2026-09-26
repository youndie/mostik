---
id: B-11
title: "native-service-bootstrap names keel as its reference project in Step 0"
status: done
priority: P1
size: S
stage: m4-consumer
blocked_by: [B-09]
---

# B-11 — The skill's Step 0 currently points at two production services

`native-service-bootstrap` is already the tenth skill in kotlin-skills — the brief's "move it there"
deliverable is done, verified in
[research-architecture](../research/research-architecture.md) §1.11. What is not done is keel being
its reference project: Step 0 today says *"copy a living service, not the template in this file"* and
names metrik and katcher.

- **The decision and its reason.** That instruction was right when the alternative was a template
  inside a markdown file, which rots the moment a convention moves. It is the wrong instruction once
  a repository exists whose CI proves it still builds: metrik and katcher carry a domain, and an
  agent copying one starts by deleting things it does not understand.
- What changes is one step, not the skill. The skill's own split — "this file describes and measures;
  the convention compels" — stays exactly as it is, and keel is a third category: *the thing that is
  already wired*.
- **After [B-09](B-09-first-consumer.md), not before.** Pointing a skill at a template nobody has
  built a service from is how a recommendation gets made on the strength of an intention.
- The alternative of leaving Step 0 alone and mentioning keel further down is worse in the one way
  that matters: an agent reads Step 0 and acts on it.

- AC: Step 0 names keel, with the clone line and what to rename; the two services stay named as
  *where the idioms are newer*, which is a different question.
- AC: the skill's paragraph about keel carries the measured numbers from
  [B-08](B-08-clone-to-ready.md), not estimates.
- Anchors: `kotlin-skills/plugins/kotlin-fullstack/skills/native-service-bootstrap/SKILL.md`,
  `kotlin-skills/plugins/kotlin-fullstack/skills/native-service-bootstrap/references/`

---

## Iteration 1 — 2026-09-16: written, and waiting on a repository this loop does not merge in

[kotlin-skills#6](https://github.com/youndie/kotlin-skills/pull/6) rewrites Step 0 to clone keel, and
closes [kotlin-skills#5](https://github.com/youndie/kotlin-skills/issues/5) with the TLS gotcha B-09
found.

| AC | |
|---|---|
| Step 0 names keel, with the clone line and what to rename | done |
| the two services stay named as *where the idioms are newer* | done — "copy structure from keel, style from them", with the note that when they disagree the living service is usually right |
| the paragraph carries the measured numbers rather than estimates | done — 249 renaming, 178 domain, 6 neither, and 3 min 48 s clone-to-ready |

**The item stays `wip` until that pull request merges, and the loop does not merge it.** keel's
`CLAUDE.md` authorises the loop to merge its own work *in keel*; kotlin-skills has no such policy and
inventing one for somebody else's repository is not the loop's to do.

### One thing found while working there

`feat/kore-stage-concurrency` in kotlin-skills is pushed, one commit ahead of `main` — `b35c309`, the
`stopAndJoin()` note — with **no open pull request**. It was not touched: this work branched from
`main` beside it. Flagged in the pull request in case it was forgotten rather than parked.

---

## Closed 2026-09-16

[kotlin-skills#6](https://github.com/youndie/kotlin-skills/pull/6) merged as `cdcc0e2`, and
[#5](https://github.com/youndie/kotlin-skills/issues/5) closed with it. Verified in the file on
`origin/main` rather than in the label: Step 0 is *"Start from keel"* with the clone line, and the
TLS gotcha is in the gotchas list.

**keel is the reference project for `native-service-bootstrap`.** Which makes the next change to keel
somebody else's problem too — a clone that breaks is now a skill that broke, and B-17's convention
adoption is the first thing that will test that.
