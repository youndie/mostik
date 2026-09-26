---
id: B-04
title: "Two images from one Dockerfile, both measured against a budget declared first"
status: done
priority: P0
size: M
stage: m2-image
epic: feature-item-round-trip
blocked_by: [B-01]
---

# B-04 — The image, and the number beside it

sborka's `writeNativeDockerfile` produces the reference two-stage file; keel commits it, fills the
two holes, and adds the `STATIC=1` variant behind a build arg. The budgets were declared in the
brief before any commit — under 25 MB on `distroless/cc`, under 12 MB with `STATIC=1` — and this item
is where they meet a real image.

- **The decision and its reason.** `gcr.io/distroless/cc-debian13` is the default and `scratch` is a
  flag ([research-architecture](../research/research-architecture.md) D3). `distroless/base` is not
  a candidate at all: Kotlin/Native's exception handling imports thirteen `_Unwind_*` symbols from
  `libgcc_s`, which `base` does not carry, and the failure is at exec.
- **No `COPY` line other than the binary.** An earlier generation of these Dockerfiles dragged
  `libcrypt.so.1` out of the builder and carried a rule that the builder's glibc must be no newer
  than the runtime's. `sborka.kmp` links with `--as-needed`, the declaration goes away, and so does
  the rule. If the image ever fails with `cannot open shared object file`, the answer is that the
  convention did not apply — not another `COPY`.
- **`MALLOC_ARENA_MAX=2` carries its counter-example, not just its value.** It was measured on a
  different service; with `-Xallocator=std` the same line took a peak from 39.3 MB to 413.7 MB and
  survivals from 10/10 to 7/10. The comment says re-measure, and says what a positive control is.
- The `STATIC=1` stage copies five paths **out of the build stage** — `/etc/ld.so.cache`, the loader,
  `libc.so.6`, the whole `gconv` directory and `zoneinfo`. Not from the host and not from another
  image of the same version: `dlopen` from a static binary needs the same glibc *build* as the
  `libc.a` it was linked against.
- A budget that is missed gets a new number **and a line saying what it bought**. A quiet edit is how
  a budget stops being one.

- AC: both images build from the committed `Dockerfile`; both answer `/health/ready`; the static one
  additionally passes the rendered-page smoke of [B-05](B-05-parity-smoke.md)'s harness, because a
  status code is not evidence there.
- AC: both sizes are recorded in the README with the date, against the declared budgets.
- Anchors: `Dockerfile`, `server/build.gradle.kts`,
  `sborka/build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/internal/NativeImageReference.kt`,
  `sborka/docs/research/research-static-binary.md`

---

## Iteration 1 — 2026-09-16, done for the image that ships

The default image builds, runs, serves a rendered page and stops cleanly. **13 972 497 bytes against
a 25 MB budget** — 44 % under.

| AC | Evidence |
|---|---|
| builds from the committed `Dockerfile` | `docker build -t keel:cc .`, 1m53s with a warm `~/.konan` cache mount |
| answers `/health/ready` | `ready` |
| under the declared budget | 13 972 497 bytes |
| a rendered page, not a status code | `POST` then `GET /items` returns `ренденная страница` intact through glibc `iconv` |
| stops cleanly | `docker stop` → exit code **0**, with kore's full transcript in the logs |

### The size had to be measured three ways before it could be reported

`docker image inspect --format '{{.Size}}'` says **13 972 497**. `docker images` says **55.4MB**.
They disagree by 4x, and reporting the second would have failed a budget that is not actually missed.

`docker save keel:cc | wc -c` gives **14 003 712** — the same number plus tar metadata — which
settles it: the image is ~14 MB for `linux/amd64`, and `docker images` is counting every platform of
the base image's manifest in the containerd store. `docker history` agrees: 9.24 MB for the binary
layer and ~4.7 MB of base.

**So the figure published in the README names its method.** A budget checked with the wrong command
is a budget that fails at random.

### Two things the image build found

**`/version` answers `0.1.0+unknown` when built here**, because the mutagen replica ignores VCS
directories, so the build context has no `.git` and the Gradle plugin has nothing to read. In a git
checkout it would name the commit. The consequence is written into `.dockerignore`: `.git` is
deliberately *not* excluded there, with the reason, because excluding it is the obvious thing to do
for context size and it silently removes the one answer `/version` exists to give.

**There was no `.dockerignore` at all**, so the first build shipped the whole tree — including
`build/`, tens of megabytes of exactly what the image build is about to produce — into the context.
Now 886 B.

### Deliberately not done, and it narrows this item

**The `STATIC=1` variant is not here.** It is not a task but a decision: sborka refused to carry the
same recipe as a convention option because it pins five `konan.properties` keys JetBrains may change
in any patch release, and a template is that hazard with a longer fuse — a convention is fixed once
for everyone, a template is copied and never updated again. [B-16](B-16-static-image.md) carries the
question, the measured prize (~4.5 MB on an image already 44 % under budget) and a recommendation.
