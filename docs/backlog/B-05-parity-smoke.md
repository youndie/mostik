---
id: B-05
title: "The parity normaliser is written before the first parity run"
status: done
priority: P1
size: S/M
stage: m3-measured
epic: feature-item-round-trip
blocked_by: [B-02, B-03]
---

# B-05 — Parity, against a normaliser that existed first

Acceptance 4 of the brief: the same k6 scenario against the JVM and the native binaries produces no
diff after the declared normaliser. The order is the whole item — the normaliser is committed
**before** the first run.

- **The decision and its reason.** A normaliser written after a red run is a list of whatever
  differed, and it absorbs the next real divergence without anybody noticing. Written first, it is a
  statement about what the two platforms are allowed to disagree on.
- What it declares, from [research-architecture](../research/research-architecture.md) D6: the exit
  code (`0` native, `143` JVM — both correct), the `Server` header, `Date`, and `/version`'s build
  time. Everything else is a diff and fails.
- The rejected alternative is comparing latencies. That is a measurement, not a parity check, and it
  belongs to [B-13](B-13-first-measurement-on-the-stand.md).
- Not covered: sborka's `sborka.parity` convention, which asks the *platform* (hostname resolution,
  connect, environment) rather than the application. It is worth applying later and is a different
  question.

- AC: `k6/items.js` runs against both binaries; the recorded responses are identical after the
  normaliser; the normaliser file's commit predates the first run's.
- AC: the harness asserts a **rendered body**, not a status code — the check the static image needs.
- Anchors: `k6/items.js`, `k6/normalise.js`, `.github/workflows/check.yaml`,
  `sborka/docs/research/research-parity.md`

---

## Iteration 1 — 2026-09-16, done

**No diff after the declared normaliser**, with a positive control on the record count.

| AC | Evidence |
|---|---|
| the scenario runs against both binaries | 160 checks passed, 0 failed, on each |
| the recorded responses are identical after the normaliser | 3 responses each, `diff` clean |
| the normaliser's commit predates the first run's | `test(parity): declare the normaliser before running anything` is its own commit, and it ran nothing |

### The first run failed, and every reason was in the harness

**The first "NO DIFF" was two empty files.** The collector was a module-level array pushed to from
the default function and written out in `handleSummary` — which compiles, runs, and yields nothing:
k6 runs `handleSummary` in its own context, and each VU has its own module instance. It fails
silently in the worst direction, because an empty comparison passes. The run now asserts a non-zero
record count and that both sides recorded the same number before it believes a clean diff, and the
responses are printed rather than collected.

**The recording pass collided with the load pass.** Both used `parity-<vu>-<iter>` against one
database, so the recorded `POST` hit a row the load pass had already inserted and what got compared
was the primary-key violation. A recording run now has its own id namespace.

**Two defects in the normaliser itself, both of the same kind — written from assumption rather than
from a response:**

* it dropped `built_at`, `commit` and `release` from `/version`. The body carries `version:` and
  `built:`. Nothing failed, because both artefacts share one generated build identity and the
  timestamps matched — a normaliser that normalised nothing, looking correct, and due to start
  failing the first time the two were built a second apart;
* it compared headers in emission order, which the two engines do not share, and kept
  `content-length` on a body it had just shortened. The first is not a difference — a header set is
  unordered; the second would fail a run for a `commit` of `unknown` against a hash.

### The one real divergence found, and why it is not in the scenario

The two drivers word a primary-key violation differently, which is what the collided run exposed:

```
native  [Database] :: [1555] (code: 1555) UNIQUE constraint failed: items.id
jvm     [Database] :: [Database] :: [SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed
        (UNIQUE constraint failed: items.id)
```

Both are `500`, and B-02 had already decided not to pin that text — the suite asserts that a duplicate
fails, not what it says. So the parity scenario exercises the success path and this stays a documented
divergence in [endpoint-items](../api/endpoint-items.md) rather than an entry in the normaliser.
**Normalising it away would have been the wrong move**: an entry in that file is a statement that two
platforms may legitimately differ, and this one is a route with no error handling, which is a gap
rather than a platform difference.
