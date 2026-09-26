---
id: measurements-2026-09-16
title: keel — the first measurement on the stand
type: research
status: active
date: 2026-09-16
---

# The first measurement on the stand

Three numbers about the `linuxX64` binary: time to ready, resident memory at ready, and p95 at a
fixed rate. Taken on two hosts, which is the only reason they are called a measurement —
[B-13](../../backlog/B-13-first-measurement-on-the-stand.md) and research D4.

## The stand

| | subject | generator |
|---|---|---|
| host | `bench-b` | `bench-a` |
| cores / RAM | 4 / 7 746 MB | 4 |
| OS | Ubuntu 26.04.1 LTS | Ubuntu 26.04.1 LTS |
| link | `10.0.0.3` ← private, 1.2 ms rtt, 0 % loss → `10.0.0.2` | |

**The roles are the opposite way round from the container-limit study**, and deliberately: `bench-a`
runs a k0s control plane and idles at load 0.37, `bench-b` at 0.03. A measurement taken on the busy
host would be measuring etcd as much as keel, which is the contention `--stand` exists to rule out —
so it was moved rather than tolerated. **The consequence is that these numbers are not directly
comparable to that study's**, which used `bench-a` as the subject.

## The numbers

Subject: `keel` 9 228 056 bytes, `linuxX64` release, SQLite on a local file, started fresh.

| | |
|---|---|
| time to ready | **0.029 s** |
| RSS at ready | **14 136 kB** — 13.8 MB |
| p95 at 500 req/s | **1.86 ms** (median of three; runs at 1.86, 1.86, 2.79) |
| p99 at 500 req/s | **3.71 ms** |
| delivered rate | **499.95/s** of 500 asked — the rate was achieved, not approximated |
| checks | 45 000 per run, **0 failed** |
| RSS after 4 runs (≈60 000 rows) | 58 120 kB — 56.8 MB |

Four runs of 30 s; **the first is discarded** and the median of the rest reported. The raw k6 summary
of the final run is [`measurements-2026-09-16/k6-summary.json`](measurements-2026-09-16/k6-summary.json)
— a conclusion without its rows is not re-checkable. The document is a file and the raw output a
directory beside it, because the documentation format counts documents at the top of a layer and a
report buried one level down is a report nothing lists.

**13.8 MB resident at rest** is the number that says `fixedBlockPageSize=16` is doing its job: katcher
measured 22–26 MB at rest with it and 56–68 MB without.

**The RSS growth is the stored rows, not a leak** — 60 000 deliveries were inserted across the four
runs and never removed. It is reported because it was observed, not because anything follows from it;
establishing a leak would need a run at constant row count, which this was not.

## What the measurement found before it produced a number

**`GET /items` cannot be in a fixed-rate profile, because it has no limit.** The first attempt drove
the full scenario and produced: 500 iterations/s asked, **29.8 achieved, 4 692 dropped**, 659 ms per
iteration, 151 MB transferred in ten seconds. Every one of those numbers describes a response body
growing by 500 rows a second, not a service under load.

That is a finding about **keel**, not about the measurement: a template's example route returns its
whole table, and every clone inherits that. It is [quirk 18](../../services/keel-server.md) now. The
scenario gained `KEEL_MEASURE=1`, which skips the list — the parity and smoke runs keep it, because
there its body is the point.

**And the scenario could not be run twice against one database.** The ids were deterministic, so run 2
collided with run 1 on the primary key and 1 886 checks failed — which looks exactly like a service
degrading under repetition. Ids are namespaced per run now.
