---
id: B-06
title: "CI proves a suite ran on each target, rather than proving the build was green"
status: done
priority: P0
size: S
stage: m1-ships-twice
epic: feature-item-round-trip
blocked_by: [B-01]
---

# B-06 — A green build that visited nothing is the failure mode here

The brief's red list includes "a check that passes on one target and is skipped on the other", and
that is not hypothetical. In kore, a green `build` on one host meant `linuxArm64Test` **did not
exist** — Kotlin/Native has no `linux_arm64` host, so the plugin never creates the task — while
`macosArm64Test` was *disabled*. One word, "SKIPPED", stood for two different states for weeks.

- **The decision and its reason.** CI's build job ends by printing every test result file with its
  counts and failing when there are none. A suite that ran zero tests exits zero, so the only
  evidence that a target was tested is a result file with a number in it.
- For the target this host cannot run, linking and running are separable: CI links the test binary on
  x86-64 and **executes** it on an arm64 runner, which is what kore does since its own B-49.
- The rejected alternative is trusting `BUILD SUCCESSFUL` through a pipe. It has been wrong in this
  portfolio before; a result file and an artefact timestamp have not.
- Not covered: mutation testing. `sborka.mutation` exists and is deliberately not wired into `check`.

- AC: the build job fails when a target's result file is missing or reports zero tests, and the
  failure names the target.
- AC: `keel-server` §8's thirteenth quirk is verified as written — a local green build on one host is
  demonstrated not to cover the other.
- Anchors: `.github/workflows/check.yaml`, `Makefile`,
  `kore/CLAUDE.md`

## Note added 2026-09-16, after B-01 merged

**There is no build job in CI at all**, so today a pull request that does not compile is green. B-01
closed against its own acceptance — a build that runs on the Linux box — and left CI alone; the
workflow's comment claimed the job had arrived with it, and that has been corrected.

This item was sized against "prove a suite ran on each target". It now also carries "run the build in
CI in the first place", which is the cheaper half and the one everything else waits on. **It became
more urgent the moment the loop started merging its own pull requests on green CI** (`CLAUDE.md`, the
loop section): green currently means the documentation gate passed and says nothing about the Kotlin.
`renovate.json` declines `automerge-harness` for exactly this reason, and that preset can be added in
the same change that closes this item.

---

## Iteration 1 — 2026-09-16, done

CI has a job that compiles the code, and 12 tests run on each of the two targets keel declares.

| AC | Evidence |
|---|---|
| the build job fails when a target's result file is missing or reports zero tests, and names the target | the report step loops over **named** targets — `jvmTest`, `linuxX64Test` — rather than counting files |
| quirk 13 verified as written | `linuxArm64Test` is not created even with the target enabled, and nothing runs it; see below |

**The targets are named rather than counted, and that is the whole step.** `find | wc -l` passes when
`linuxX64Test` quietly stops being wired and the JVM suite carries the run on its own — which is the
brief's red list exactly: *a check that passes on one target and is skipped on the other*. Naming
them means the failure says which one went missing.

### The tests were checked by mutation, not by being green

Committed first, then two mutations, each restored and rebuilt afterwards:

| Mutation | What died |
|---|---|
| drop `pairs` from `KeelConfig.SCHEMA` | `half of the observability pair is refused`, `every problem is reported rather than the first` — and nothing else |
| drop `installKoreProbes` from `keelModule` | the three probe tests — and nothing else |

The second is why the route tests go through `keelModule` rather than through `itemRoutes()`: a test
of the route function alone passes with the probes and the shutdown refusal missing entirely.

### What the tests found on the way

**A test written against an assumption failed on both targets, and the assumption was mine.**
`StartupGate()` with no named gates is started from birth — `started = gates.isEmpty()` — so keel's
startup probe answers `200` from the moment the module is installed. The probe means nothing until a
service names what it is waiting for, which is now quirk 14 and a scenario, with both halves
asserted so the next person meets the behaviour rather than rediscovering it.

**`keel-server.md` §7 described a schema keel does not have.** It said "two have defaults", which was
never true of the code: it described kore's sample, which the schema was modelled on and which has a
`WORK_MS` keel has no equivalent of. The feature's misspelling scenario named `KEEL_WORK_MSEC`
against a `KEEL_WORK_MS` that does not exist. Nothing noticed until a test had to count the keys.
Both corrected; §7 is now a table of the four keys and the shape each one is an example of.

### Deliberately not done

**`linuxArm64` is built by nobody and tested by nobody**, and it is not in this item's named-target
list. It cannot be: with two native targets razves registers `sizeReportDebugExecutable` twice and
the build fails at configuration, so `-Pkeel.linuxArm64=true` does not build at all while
`sborka.binaryBudget` is set — which is every build here. Filed as
[razves#3](https://github.com/youndie/razves/issues/3) per the routing table;
[B-15](B-15-arm64-suite-runs.md) carries the job and waits on it.

That is a narrower closing than the item's title suggests, so it is said plainly: *each target* here
means the target set the build declares by default. The third one is property-gated, and covering it
is a separate item rather than a silent omission.
