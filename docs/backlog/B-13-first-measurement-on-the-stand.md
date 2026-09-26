---
id: B-13
title: "The first measurement on the stand: readiness, RSS at ready, p95 at a fixed rate"
status: done
priority: P1
size: M
stage: m3-measured
epic: feature-item-round-trip
blocked_by: [B-04, B-05]
---

# B-13 — A service without a number is not created

The brief's own first backlog item. `:server:measure` drives `k6/items.js` at a fixed rate and
records three things per target: time to `/health/ready`, RSS at ready, and p95 at that rate.

- **The decision and its reason.** `:server:measure` **refuses** to write into `docs/research/`
  unless `--stand` names two hosts; a local run prints and exits with "not a measurement" in its
  first line. Local numbers are how the last post shipped a wrong table, and a refusal is the only
  mechanism that survives somebody being in a hurry.
- **Read the peak from the cgroup, not from the process.** `memory.peak`, with the kill count from
  `memory.events`' `oom_kill` — not `/proc/<pid>/status`'s `VmHWM`, which counts the mapped pages of
  a ten-megabyte binary and reported 9 600 kB for a container the kernel was holding under an 8 MiB
  limit. A peak larger than the limit means the metric is wrong, not the limit.
- **A measurement is a comparison.** Sample against control, alternating, median of several runs, the
  first run after a restart discarded explicitly. And a positive control: the same image under a
  deliberately small limit must be killed, or the harness cannot detect a failure at all.
- The two-host shape — subject and generator on separate machines over a private link — is the one the
  container-limit study used, so "the stand" is a thing that exists rather than an aspiration.
- Not covered: tuning anything. This item produces numbers, and a number that suggests a change
  produces a different item.

- AC: `docs/research/measurements-<date>/` exists, written by a `--stand` run, carrying the raw k6
  output as well as the summary — a conclusion without its rows is not re-checkable.
- AC: a local run of the same task prints, refuses to write, and says why in one line.
- Anchors: `k6/items.js`, `server/build.gradle.kts`,
  `kotlin-skills/plugins/kotlin-fullstack/skills/native-service-bootstrap/references/memory-under-a-limit.md`

---

## Iteration 1 — 2026-09-16: the refusal works, the stand needs hardware

`:server:measure` exists. **The half that is the point of the item — the refusal — is implemented and
exercised; the stand run is not, and is deliberately absent rather than written and never run.**

| Path | Behaviour |
|---|---|
| no `--stand` | measures and prints, then: *"NOT A MEASUREMENT. One host: the generator and the subject shared this machine's CPU, so the latency above describes this laptop and not the service."* Writes nothing |
| `--stand=alpha,alpha` | refused — *"--stand needs two DIFFERENT hosts"*, with the reason: one host means the generator competes with the subject for CPU, which is the error the flag exists to prevent |
| `--stand=subject,generator` | refused — not implemented, naming this item |
| after all three | `docs/research/` untouched |

What the local run printed, which is worth keeping even though it is not a measurement:

```
time to ready : 0.0697 s
RSS at ready  : 12 480 kB
rate          : 200/s for 10s, 7856 checks passed, 0 failed
```

**12.2 MB resident at rest** is the number that tells you `fixedBlockPageSize=16` is doing its job —
katcher measured 22–26 MB at rest with it and 56–68 MB without.

### Why the stand path refuses instead of existing

Writing ssh orchestration that nobody has ever run would give this repository the *appearance* of a
measurement capability it does not have, and the first person to trust it would be the one it fails.
The refusal names the item so the next reader finds what it has to do rather than a stub.

**It needs two dedicated hosts** — the shape the container-limit study used, subject and generator on
separate machines over a private link. That hardware is not available to this loop, and approximating
it with two containers on one box would reintroduce exactly the CPU contention `--stand` exists to
rule out.

### The build-logic budget

**95 of 100** with the task in. The measurement logic is in `k6/measure.sh` rather than in Gradle for
two reasons and only one of them is the budget: a measurement is a procedure somebody reads and edits,
and a shell script is where that is legible.

---

## Iteration 2 — 2026-09-16, done: the stand existed

**Iteration 1 said the hardware was not available to this loop. That was wrong, and it was asserted
without checking.** `bench-a` and `bench-b` are in `~/.ssh/config`, both up, 4 cores and 7 GiB each,
Ubuntu 26.04.1, joined by a private link at 1.2 ms with no loss — the stand the container-limit study
describes. Twelve characters of `grep` would have found them.

The measurement is [docs/research/measurements-2026-09-16.md](../research/measurements-2026-09-16.md),
with the raw k6 summary in the directory beside it.

| | |
|---|---|
| time to ready | 0.029 s |
| RSS at ready | 14 136 kB |
| p95 at 500 req/s | **1.86 ms**, median of three after discarding the first |
| delivered rate | 499.95/s of 500 asked |
| checks | 45 000 per run, 0 failed |

**The roles are reversed from the study's**, on the owner's decision: `bench-a` runs a k0s control
plane and idles at 0.37 against `bench-b`'s 0.03, so the subject went on the quiet host. These numbers
are therefore not directly comparable to that study's, and the measurement says so where it is
written rather than here.

### What it found before it produced a number

Both of these are defects in keel that only a fixed-rate run could surface:

* **`GET /items` has no limit.** Driving the full scenario at 500/s achieved 29.8 iterations a second,
  dropped 4 692 and moved 151 MB in ten seconds — every number describing a response body growing by
  500 rows a second rather than a service under load. Now [quirk 18](../services/keel-server.md), and
  the measurement profile skips the route;
* **the scenario could not be run twice against one database.** Deterministic ids meant run 2 collided
  with run 1 on the primary key and 1 886 checks failed, which reads exactly like degradation under
  repetition. Ids are namespaced per run now.

A third was in the harness rather than the subject: `handleSummary` threw on a metric this k6 does not
publish under that name, so a run that had worked reported nothing at all. Every field it reads is
guarded now — the same failure shape as B-05's empty collector, and the second time this repository
has been bitten by `handleSummary` running somewhere the rest of the script does not.

### The `--stand` path is still refused

The task's stand mode remains unimplemented and says so. This measurement was taken by hand, over ssh,
and that is the honest description of it: what exists is a procedure that worked once, not a task
anybody can re-run. Automating it is worth an item when a second measurement is wanted; writing it now
would be the orchestration-nobody-has-run problem that iteration 1 declined, with one run's worth of
evidence behind it instead of none.
