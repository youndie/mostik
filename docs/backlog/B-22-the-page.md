---
id: B-22
title: "The kotlin.website page: a Kotlin server that ships twice, in an hour"
status: wip
priority: P1
size: M
stage: m4-consumer
---

# B-22 — The deliverable that was never an item

The brief lists three deliverables. Two are done — the template repository, flagged as one, with the
measured numbers in its README; and `native-service-bootstrap` pointing here with an eval suite beside
it. The third is a page on kotlin.website, *"A Kotlin server that ships twice, in an hour"*, and it
**was never entered into this backlog at all**.

That is the finding, and it is worth more than the page: twenty-one items were written from the
brief's contents table, its acceptance list and its decisions, and the deliverables section was read
past. A backlog derived from a document can inherit the document's shape and still miss a section of
it, and nothing here would have noticed — every gate is about internal consistency, and a missing
deliverable is consistent with everything.

- **What the page is, per the brief.** The numbers and the clone line, and **nothing about how kore or
  sborka work inside**. That restriction is the whole editorial problem: most of what was learned here
  is about those two, and none of it belongs on this page.
- **The numbers it has to carry**, all measured and dated in this repository: 3 min 48 s from clone to
  both halves answering `/health/ready` on a machine that has never seen the portfolio; 13 972 497
  bytes of image; p95 1.86 ms at a delivered 499.95 req/s on two hosts; 23 tests on each of three
  targets; the first consumer's six lines that were neither domain nor renaming.
- **What it must not do is claim the stranger test.** A clone still needs the portfolio's Maven
  repository configured, because kore, sborka and razves are not on Maven Central. A page that says
  "clone and run" without that line is wrong for everybody outside this portfolio, which is most
  readers.
- Not covered: moving those three to Central. That is not keel's work and the README already carries
  the reposilite block.

- AC: the page exists on kotlin.website, carrying the clone line and the measured numbers with the
  dates they were taken.
- AC: it says what a reader needs before cloning — the repository block — rather than discovering it.
- AC: it explains none of kore's or sborka's internals.
- Anchors: `README.md`, `docs/research/measurements-2026-09-16.md`, `backlog.md`

---

## Iteration 1 — 2026-09-16: written, waiting on a repository this loop does not merge in

[vedutsya-raboty/kotlin-website#24](https://github.com/vedutsya-raboty/kotlin-website/pull/24) —
`AKotlinServerThatShipsTwice.md`. No registration needed: `Site.kt` says dropping a `.md` into
`resources/markdown/blog/` is the whole of it and `BLOG_ENTRIES` is generated.

| AC | |
|---|---|
| the clone line and the measured numbers with their dates | done — and each carries the conditions it was taken under |
| says what a reader needs before cloning rather than letting them discover it | done — the reposilite block, in its own section, before the reader can hit it |
| explains none of kore's or sborka's internals | done |

### Two numbers were wrong and checking caught them

Written from memory, corrected against the record before committing:

* the binary is **9 228 056** bytes, not 9 227 448 — and the README was carrying the older figure too,
  from when B-04 weighed the image, before B-03 and B-20 moved it by 608 bytes. Both were true when
  written; only one is true now, and the two documents now agree with each other and with the binary;
* resident at rest is **14 136 kB**, the figure from the same stand run the p95 came from, not the
  13 968 I had put down.

Neither would have been caught by any gate here. A page is prose, and prose that quotes a number is
exactly where the checks in this repository stop.

### Not merged

kotlin-website is not this repository, and keel's `CLAUDE.md` authorises the loop to merge its own
work *here*. The item closes when a person merges it.

**Its state is worth a line for whoever does:** `KotlinNativeFromScratch.md` is modified and
uncommitted there, and two `medium-*.md` files are untracked at the root. Branched from `main`, left
alone, flagged in the pull request.
