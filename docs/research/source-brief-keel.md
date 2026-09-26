---
id: source-brief-keel
title: Brief — keel as it arrived
type: research
status: active
date: 2026-09-16
---

# Source brief: keel, the service starter

Kept verbatim, as the input rather than a finding.
[research-architecture](research-architecture.md) §2 lists where it turned out to be asking for
something that could not be done, or for something that had already been done differently — D1, D3
and D5 in particular. The text below is unedited.

---

## keel — a template repository for a Kotlin server that ships twice

Name: **keel** — the first member laid down, everything else is built on it. Alternatives if it
reads wrong: `chassis`, `groundwork`, `bedrock`. One syllable, no transliteration, not "starter" or
"template" in the name because those describe the mechanism and not the thing.

## What it is

A GitHub template repository. Clone, rename, `./gradlew run`, and there is a service: one route, one
table, three probes, a `/version`, two targets both runnable, one image, every check green on day
one. It is konekt with the domain removed — the wiring a new service needs, and nothing the last
one happened to have.

It is not a library and publishes nothing. It is not a framework: kore owns the lifecycle, sborka
owns the build, keel only shows them wired. The test for every file in it: *did konekt or katcher
need this?* If not, it is not in keel.

## Non-goals

- **No client.** kompot and Compose belong to a second template, in the territory of mani and
  kotlin-skills. Mixing them is how konekt got to 300 pages.
- **No domain.** The one entity is `Item { id, name }` and it stays that way. A starter whose
  example grows features stops being a starter.
- **No build logic of its own.** Every convention lives in sborka; keel's build files apply
  conventions and set names. A flag that appears in keel's `build.gradle.kts` is a flag sborka
  forgot.
- **No documentation of the stack.** The docs tree is docs-bootstrap's shape with keel's own
  service described; how kore or sborka work is documented where they live.

## Contents

| | | reason it is here |
|---|---|---|
| `:server` | one KMP module, `jvm()` + `linuxX64` (+ `linuxArm64` behind a property) | parity research: every service had `jvm()` and none had a runnable JVM — keel has both, `installDist` and `linkReleaseExecutable*` |
| one route | `GET/POST /items`, JSON via kotlinx.serialization | exercises URL encoding (the gconv path) and the CIO parser path (the lock) |
| one storage port | `ItemStore` in common, per-build implementation — decision D1 below | ktor-server-feature skill's own rule |
| kore | `installKoreProbes`, `installKoreVersion`, `runUntilSignal` with `announce → drain → pool`; `ConfigSchema` for the four keys keel has | the shutdown order is the product, and the first consumer found seven defects — keel is the second |
| sborka | `sborka.native-service`: `fixedBlockPageSize=16`, `--as-needed`, explicit API, ktlint, size budget, `static` option | the findings of the last two weeks as defaults, not as README advice |
| zavarnik | AOT cache for the JVM distribution, `aotVerify` on `check` | the JVM half is shipped, not only tested |
| `Dockerfile` | two stages; runtime `distroless/cc` by default; `scratch` behind `--build-arg STATIC=1` with the four gconv lines and the curl caveat in a comment | the honest base today, and the recipe next to it |
| CI | build both targets, test both, image build, size budget, parity smoke, `docs_check.py` | what a service has to pass, on the first commit |
| `k6/` + `:server:measure` | readiness, RSS at ready, p95 at a fixed rate; local run labelled "not a measurement", stand run writes `docs/research/measurements-<date>/` | a service without a number is not created |
| `docs/` | docs-bootstrap tree: `service` doc with `quirks` pre-filled (the five platform divergences), one `feature`, one `endpoint`, empty research slot, backlog with `B-1 first measurement on the stand` | day one passes `make check`; the quirks are the ones every native service inherits |
| `CLAUDE.md` | points at the skills: `native-service-bootstrap`, `ktor-server-feature`, `kmp-testing`, `backlog-item` | the agent reads the repository before the skill, per Step 0 |

Optional, off by default, each one commented block: a chronik timer (if chronik has a native
artefact — D2), a booblik topic through `kore-booblik`, `kore-observability` (portfolio-only, and
the comment says so).

## Decisions to take, with what settles them

- **D1 — storage on two targets.** Options: sqlx4k on both if its JVM artefact is real and
  resolves from Central; otherwise sqlx4k on native and Exposed/JDBC on the JVM behind the port.
  Settled by one build: two implementations of `ItemStore` are the price of a runnable JVM, not a
  design flaw, and the skill already prescribes the port.
- **D2 — chronik on native.** Settled by reading chronik's targets. If JVM-only, keel's timer slot
  is a documented absence in the service doc — which is information, not a gap to hide.
- **D3 — image default.** `distroless/cc` until KTOR's gconv issue lands; `scratch` stays a flag.
  Settled by the ticket, not by preference.
- **D4 — where the measurement runs.** `:server:measure` refuses to write into `docs/research`
  unless `--stand` names two hosts; a local run prints and exits. Settled: local numbers are how
  the last post got a wrong table.

## Acceptance — declared before the first commit

**Green** — all of:
1. A clone with the portfolio's repository configured builds both targets, passes `check`, builds
   the image, and answers `/health/ready` on JVM and native, on a machine that has never seen the
   portfolio, in under an hour of wall time including the toolchain fetch.
2. The image is under 25 MB on `distroless/cc`; under 12 MB with `STATIC=1`.
3. `kill -TERM` under load finishes every in-flight request — kore's oracle, run against keel's
   binary, both targets.
4. Parity smoke: the same k6 scenario against the JVM and native binaries produces no diff after
   the declared normaliser.
5. `make check` green on the docs tree.
6. Kotlin in `:server` under 500 lines; Gradle in the repository under 100. Over either is the
   signal that something belongs in sborka or kore instead.

**Red** — any of: a flag in keel's build files that sborka could carry; a second entity; a check
that passes on one target and is skipped on the other.

**Deferred, and said so:** the stranger test — the same clone with *no* portfolio repository —
waits for kore-core, kore-ktor and sborka on Central, which is a month out. Until then the README
carries the reposilite block with the date it is expected to go.

## First consumer, and where defects go

The webhook relay is built *from* keel through the `native-service-bootstrap` skill, by the agent,
with the human counting. Every line the agent had to add that is neither domain nor template
renaming is a defect, filed in one of three places:

| the line was | it goes to |
|---|---|
| a build flag, a linker option, a CI step | sborka |
| lifecycle, probes, config, shutdown | kore |
| a procedure the agent had to work out | the skill |

Nothing goes to keel except renaming. If keel accumulates fixes, it is turning into konekt.

## Kill criteria

- D1 has no implementation that resolves on both targets from a public repository → keel ships
  JVM with an in-memory store and native with sqlx4k, and the service doc says the JVM half is a
  test double. Not a stop, but the README says it in the first paragraph.
- Acceptance 6 cannot be met after the first consumer → the starter idea is wrong for this stack
  and the honest deliverable is the skill alone, generating from konekt's pieces. Write that down
  and stop.
- Time box: one week to green on 1–6 with the repository configured; the Central-dependent part
  is not in the box.

## Deliverables

- `github.com/youndie/keel`, flagged as a template, README with the numbers from acceptance 1–2
  and the date they were taken.
- `native-service-bootstrap` moved into kotlin-skills as the tenth skill, keel as its reference
  project, `references/` carrying the recipes with the defect named beside each (KTOR-9891,
  KT-89365, the gconv issue, the five platform divergences), `evals/evals.json` with "create
  service X" and checkable expectations.
- One page on kotlin.website: "A Kotlin server that ships twice, in an hour" — the numbers and the
  clone line, nothing about how kore or sborka work inside.
