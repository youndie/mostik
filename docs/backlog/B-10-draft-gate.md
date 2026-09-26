---
id: B-10
title: "Turn docs_check.py --on-main on once the tree describes code that exists"
status: done
priority: P2
size: XS
stage: m4-consumer
blocked_by: [B-07]
---

# B-10 — The draft gate is off with an address, not relaxed

Every document outside `research/` is `status: draft`, because the code does not exist. On `main` a
draft is a defect — it means intent was documented as fact — and `docs_check.py --on-main` is the
mechanical half of that rule. It is **off**, and this item is its address.

- **The decision and its reason.** A docs-first template repository has nothing that exists yet. The
  two honest ways to hold the invariant are to keep the whole tree in an open pull request until the
  code lands, or to say in one place that the tree is intent and name the item that turns the gate
  on. kore took the second and it worked: the flag became a line in CI rather than a relaxed rule.
- What is not acceptable is a tree of `active` documents describing code nobody has written, which is
  the failure mode this whole format exists to prevent.
- **Flipping the field is not the work.** When a feature is built, its document becomes `active` *and
  is re-read against the code* — the status is the last edit, not the first.
- Not covered: the research documents, which stay `active` throughout. Research legitimately predates
  the code, and `research-architecture` describes reading that actually happened.

- AC: `docs_check.py --on-main` runs in CI on pushes to the default branch and is green, because every
  layer document has been re-read against the code it describes.
- Anchors: `.github/workflows/check.yaml`, `Makefile`, `scripts/docs_check.py`

---

## Iteration 1 — 2026-09-16, done

`docs_check.py --on-main` runs in CI on pushes to the default branch and passes. The three layer
documents are `active`.

**They were re-read against the code, not flipped**, which is the rule this item exists to honour —
and the re-reading is the whole value, because four sentences had quietly stopped being true:

| What it said | What the code says |
|---|---|
| `server/src/linuxX64Main/.../Main.kt` | the file is in `nativeMain` — which is why enabling `keel.linuxArm64` is a property and not a second copy |
| §6: `docker build --build-arg STATIC=1 -t keel:static .` | there is no such build arg. A reader following it would have built the ordinary image and believed it was the static one |
| §5: "B-16 **asks** whether a template should carry that recipe" | B-16 answered, in the negative, with B-18 as its expiry |
| the anchor tables | `k6/measure.sh` existed and was listed nowhere |

The second is the one worth the item on its own. It is not a stale reference — it is an instruction
that **runs and produces the wrong artefact silently**, which is the exact failure mode an `active`
document is supposed to rule out and the reason flipping without reading would have been worse than
leaving everything `draft`.

### What stays honest inside an `active` tree

`active` does not mean everything described is built. It means nothing described is *wrong*. Three
things are absent and say so where a reader meets them: a `scratch` image (B-16, by decision),
`linuxArm64` coverage (B-15), and an automated stand run (B-13 — that measurement was taken by hand
over ssh, and the task still refuses).

The feature document's scenarios gained the same distinction: eight are automated, several are
**verified without being automated** and name the run that did it, and one — the `scratch` page — has
nothing to run against at all. `bdd_report.py` counts all of those as manual, and it is right to.
