---
id: B-08
title: "Clone to /health/ready on a machine that has never seen the portfolio, timed"
status: done
priority: P1
size: S/M
stage: m3-measured
epic: feature-item-round-trip
blocked_by: [B-04, B-03]
---

# B-08 — The hour, measured rather than claimed

Acceptance 1 of the brief declares an hour of wall time, including the toolchain fetch, from a clone
to a service answering `/health/ready` on both targets. Nothing has been timed. This item produces
the number and puts it in the README with the date it was taken.

- **The decision and its reason.** The figure goes in the README **whatever it turns out to be**. A
  README that promises an hour and delivers two is worse than one that says two: the first thing a
  reader of a starter does is compare the claim with their own clock.
- The hypothesis is that the time is dominated by two downloads — roughly a gigabyte of Kotlin/Native
  toolchain, and the `gradle:9.7.1-jdk25-noble` builder image — and that the `~/.konan` cache mount
  keeps the second image build off the network. If the hypothesis is wrong, what actually dominates
  is the interesting finding.
- **A machine that has never seen the portfolio** means empty `~/.gradle`, empty `~/.konan`, no
  Docker layer cache. A warm machine measures the cache.
- Not covered: the "stranger test", a clone with **no** portfolio repository configured. That waits
  for kore and sborka on Maven Central and is not keel's work; until then the README carries the
  reposilite block with the date it is expected to go.

- AC: a transcript with timestamps for each phase — toolchain fetch, `build`, `check`, image build,
  first `200` from each binary — and one line in the README.
- AC: if the hour is missed, the README says the measured number and the item records which phase ate
  it, so the next reader knows what to attack.
- Anchors: `README.md`, `Dockerfile`, `.github/workflows/check.yaml`

---

## Note added 2026-09-16: this needs B-03, and did not say so

The acceptance above reads *"answers `/health/ready` on JVM and native"*. There is no way to run the
JVM half: `application` lives in the `:distribution` module that B-03 would add, and B-03 is a
`question` because that module does not fit acceptance 6's line budget. `jvm()` today produces a jar
with no entry point wired and no start script.

So `blocked_by` gains B-03. It was missed when the backlog was written, because the dependency runs
through an acceptance criterion's wording rather than through an artefact — B-08 needs what B-03
*builds*, not what B-03 *decides*, and the two only diverged once B-03 turned out not to fit.

**The native half could be measured now** and deliberately is not. Half a number against a criterion
that names two targets is the kind of figure that gets quoted without its qualifier — and the
qualifier here is "on the target that has no distribution", which is exactly what the reader would
drop. When B-03 is answered this item measures both in one run.

**How to measure it when the time comes**, since the how is most of the work: the host's `~/.gradle`
and `~/.konan` are warm from every other repository in this portfolio, and clearing them would cost
each of those a gigabyte to recover. A container with an empty `HOME` — `gradle:9.7.1-jdk25-noble`,
clone from GitHub, build — is a machine that has never seen the portfolio without touching one that
has.

---

## Iteration 1 — 2026-09-16, done: 3 min 48 s against an hour

A fresh `gradle:9.7.1-jdk25-noble` container, 8 GB, no cache mounted, cloning from GitHub. A machine
that has never seen the portfolio, without touching one that has — the Linux box's `~/.gradle` and
`~/.konan` are warm from every repository here and clearing them would cost each of those a gigabyte.

| Phase | Seconds | |
|---|---|---|
| prerequisites | 6.99 | `git`, `curl`, `python3-yaml`, `make` — the machine is assumed to have a JDK, not these |
| clone | 1.36 | `--depth 1` from GitHub |
| **`./gradlew build`** | **187.59** | the Kotlin/Native toolchain fetch, both targets, 23 tests each, and the AOT training run |
| `make check` | 25.16 | the documentation gate |
| native `/health/ready` | 0.08 | first `200` from the staged binary |
| JVM `/health/ready` | 7.28 | of which **7 s is a deliberate wait** for the native process to release the port; the distribution itself answered in about 0.3 s |
| **total** | **228.45 s — 3 min 48 s** | against a declared hour |

**The build dominates, as the hypothesis said**, and by more than expected: 82 % of the whole run,
almost all of it the toolchain. The hypothesis also guessed the builder image pull would be the other
half; it is not in this figure at all — see below.

### What is not in the number, said rather than buried

**The image build.** The container has no Docker, so `docker build` could not run inside the timed
sequence. Measured separately on the host at **2 min 02 s** for a first build and 1 min 53 s for a
rebuild, with the base images already pulled and the `~/.konan` cache mount warm. A genuinely cold
machine would add the `gradle:9.7.1-jdk25-noble` pull — around a gigabyte — and a second toolchain
fetch inside the image build, since that cache is the daemon's rather than the clone's.

Adding the most pessimistic reading of all of that to 3 min 48 s leaves the hour untouched. The
figure published in the README is the one that was measured, with the exclusion named.

### `make check` failed on the fresh clone, and the defect was keel's

`check exit: 2`. The gate itself was **green** — index, `docs_check`, `coverage_map` all passed — and
what died was `make report`: `code_anchors.py --repos ..` with the clone at `/work`, so `..` is `/`
and it walked the entire filesystem until the kernel killed it.

Two defects, both of them a template's rather than a portfolio's:

* **`REPOS ?= ..` assumes the checkout sits beside its siblings.** True here and in CI, and false for
  a clone anywhere else — which is every clone of a template. Documented, with `REPOS=.` as what a
  clone sets.
* **The reports could fail the gate**, while the comment above them said they are not gates. They were
  run by `check`, so a report exiting non-zero took the build with it. They are prefixed with `-` now;
  they still print, and what they print is still read by a person.

The second is the one worth the line: the Makefile *said* the right thing and *did* the opposite, and
it took a clone at a filesystem root to tell the difference. A repository whose documentation was
entirely consistent went red for a report nobody gates on.
