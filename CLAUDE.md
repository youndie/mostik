# CLAUDE.md — keel

A GitHub template repository for a Kotlin server that ships twice: one source, a JVM distribution and
a Kotlin/Native binary, both runnable, both tested, one image. konekt with the domain removed.

**The template is finished against its brief.** Three targets build, 23 tests run on each of `jvm`
and `linuxX64` from one source and the same binary runs on arm64 hardware, both halves pass kore's
shutdown oracle, parity holds after a normaliser written first, the image is 13 972 497 bytes, and the
stand measured p95 at 1.86 ms. A webhook relay was built from it and needed no change to any
infrastructure file.

What is **not** done: the kotlin.website page (B-22). What is deliberately absent and says so: a
`scratch` image (B-16, B-18), an automated stand run (B-13 — the measurement was taken by hand), and
authentication, a chart and a client, which are non-goals.

This paragraph has been wrong three times — *"nothing is built"*, *"no test at all"*, *"`linuxArm64`
is built and tested by nobody"* — each true when written and false within a day. `backlog.md` and the
build are what cannot go stale; prefer them.

This paragraph said *"nothing is built"*, and then *"no test at all"*, each wrong one iteration
later. A sentence about the state
of a repository has no way to fail; `backlog.md` and the build do — prefer them. Two neighbouring
repositories in this portfolio had the equivalent sentence wrong for weeks in both directions, which
is why it is worth naming here.

## How to start a session

1. [docs/research/research-architecture.md](docs/research/research-architecture.md) — what was read
   in the artefacts and the repositories on 2026-09-16, and what follows from it. Skipping it costs a
   day per finding. The four that most often contradict what an example would lead you to write:
   - **one `ItemStore`, not two** (§1.6, D1). `sqlx4k-sqlite` publishes a real JVM variant and it is
     `org.xerial:sqlite-jdbc` underneath, so the split the brief priced at two implementations is one
     the library already carries;
   - **zavarnik refuses a project without the `application` plugin, and `application` does not apply
     to a multiplatform module** (§1.8, D5). That is why `:distribution` exists, why it is not called
     `:server-jvm` (the jar name collides with `:server`'s own), and why the root `build.gradle.kts`
     declares both Kotlin plugins with `apply false`;
   - **`scratch` needs five paths copied out of the build stage** (§1.5), and a static image's smoke
     test has to reach a *rendered page* — a `401` was once read as a pass, and every rendered byte
     goes through glibc `iconv`, which is `dlopen`ed;
   - **KTOR-9891 is fixed and is not the gconv issue** (§1.9, D3). The brief joined two unrelated
     tickets; the decision survived and its address changed.
2. [backlog.md](backlog.md) — the goal, the stages, the index. Items are one file each in
   `docs/backlog/`; the index between the markers is generated, so edit the item and run
   `python3 scripts/backlog_index.py`.
3. The layer document the task belongs to — [docs/services/keel-server.md](docs/services/keel-server.md)
   for the modules and the twenty quirks, [docs/api/endpoint-items.md](docs/api/endpoint-items.md)
   for the routes, [docs/features/feature-item-round-trip.md](docs/features/feature-item-round-trip.md)
   for the scenarios that are the template's acceptance. The map is [docs/README.md](docs/README.md).
4. The skills, when the task is building rather than documenting: `native-service-bootstrap` for the
   skeleton, `ktor-server-feature` for a route inside a service that already runs, `kmp-testing` for
   the suites, `backlog-item` for an item. The repository is read **before** the skill.

## The rule that keeps this a template

**Nothing goes to keel except renaming.** Every line that is neither domain nor template renaming is
a defect somewhere else, and it has a destination:

| the line was | it goes to |
|---|---|
| a build flag, a linker option, a CI step | [sborka](https://github.com/youndie/sborka) |
| lifecycle, probes, config, shutdown | [kore](https://github.com/youndie/kore) |
| a procedure that had to be worked out | the `native-service-bootstrap` skill |

If keel accumulates fixes, it is turning back into konekt. The mechanical half of the rule is the two
line budgets — 500 lines of Kotlin under `server/`, 100 of Gradle across the repository — and going
over either is the signal, not the failure.

## The loop merges its own pull requests

**A `/loop` iteration merges the pull request it opened, once CI is green.** One item, one branch, one
pull request, merged by the loop — `gh pr merge --rebase --delete-branch`.

This is a deliberate trade and it is worth naming rather than discovering. What is given up is
review: nobody reads the diff before it is on `main`. What is bought is a loop that runs unattended,
and without it the loop stops after one item — every other `P0` here is `blocked_by: B-01`, statuses
only change on `main` when a pull request merges, and an item whose blocker still reads `open` is not
pickable. The first iteration hit exactly that wall.

The conditions, which are not negotiable inside an iteration:

- **Green CI, read from the pull request, before merging.** Not `make check` locally — the run on the
  head commit. A merge on a pending or failing run is the whole guard gone.
- **The pull request body still carries the acceptance checklist**, ticked from evidence. The body is
  what a person reads afterwards instead of the diff, so it is the review surface and it is written
  as one.
- **A `question` item is never merged into being decided.** It goes to `main` as a `question`, and
  the loop stops there.
- **Anything routed out of keel — sborka, kore, the skill — is filed before the merge**, not after.
  A finding that exists only in a merged commit message is a finding nobody will act on.
- **`--rebase`, not squash.** The commit messages carry the reasoning; a squash collapses them into
  the pull request title and the *why* is what survives longest.

Not covered by this: a pull request a person opened. The loop merges what the loop opened.

## The two rules

- **`main` describes what exists.** Every layer document is `active` since B-10, and each was
  **re-read against the code** rather than flipped — that re-reading found an instruction in the
  service document that ran and silently produced the wrong artefact. `docs_check.py --on-main` is
  the mechanical half and it is **on**. A new document is `draft` in its pull request and `active`
  when somebody has checked it, in that order.
- **What was verified is separated from what was assumed, explicitly.** Everything in research §1
  carries a file, a coordinate or a URL with the date it was read. Everything else says "decision" or
  "hypothesis", and a hypothesis names the item that settles it. A document that blurs the two is a
  defect worth an item, because both halves then look equally authoritative.

## Rules that are cheap to follow and expensive to discover

Most of these are kore's and sborka's, restated because a keel session will not have their
repositories open. The full list with addresses is
[docs/services/keel-server.md](docs/services/keel-server.md) §8.

- **Never put shutdown work in `ApplicationStopping`.** On Kotlin/Native it runs *before* the drain
  and on the JVM *after* it, from identical source. This is the reason kore exists.
- **Never call `addShutdownHook`.** One global slot on Native, last registration wins, and the
  callback runs on the signal stack.
- **`runUntilSignal` goes after `server.start(wait = false)`**, because its default `watch` argument
  installs the handler at the moment of the call. Print the transcript **inside** `onFinished`: on
  the JVM the line after the call never runs.
- **`nativeService { }` goes above the `kotlin { }` block**, or the build fails with "property
  entryPoint has no value available" and names neither the ordering nor the place.
- **Two sibling modules applying different Kotlin plugins need the root build to declare both with
  `apply false`.** Otherwise the Kotlin plugin's shared build service exists under two classloaders
  and the build fails naming two of them and nothing else.
- **Exactly one sqlx4k driver.** Two do not link — `duplicate symbol: std::panicking::EMPTY_PANIC` —
  and it is a link error, not a resolution error.
- **`ENTRYPOINT` in exec form, always.** Shell form makes `/bin/sh -c` PID 1, which does not forward
  `SIGTERM`; the run then looks like an instant clean shutdown.
- **Never assert an exit code across the two targets.** A clean `SIGTERM` exits `0` on Native and
  `143` on the JVM. Assert the process ended itself and was not `SIGKILL`ed (`137`).
- **A chart points readiness at `/health/ready`.** `/health` is an alias for **liveness**, and a
  readiness probe there cannot fail while the process is alive.
- **Read a result file, not a log line.** `BUILD SUCCESSFUL` through a pipe has been wrong in this
  portfolio; a test-result XML and an artefact's timestamp have not. A suite that ran zero tests
  exits zero.
- **A green build on one host says nothing about arm64.** Kotlin/Native has no `linux_arm64` host, so
  `linuxArm64Test` is never created — it does not appear as skipped, it does not appear at all.
- **A number that was not measured says so**, and names the item that will measure it. A measurement
  is a comparison: sample against control, alternating, median of several runs, the first run after a
  restart discarded, and a positive control that can actually go red.
- **Do not fork a toolkit.** A gap goes upstream as an issue, the workaround stays local, and the
  workaround carries a comment naming the issue so the next person deletes it instead of inheriting
  it.

## Where things build

This repository is a mutagen session (one-way replica, alpha here, beta `keel` on the Linux box).
**Gradle runs there**, through the wrapper:

```bash
~/.claude/bin/wsl-run ./gradlew build
```

**Edits, `git` and the documentation checks stay on the Mac**, and need `LOCAL=1` to get past the
hook:

```bash
LOCAL=1 make check
```

A Mac cannot link an ELF, so the native link is a Linux-only command; a klib cross-compiles and an
executable does not.

**The replica is one way, and a Gradle task that writes into the repository loses its output there.**
`./gradlew updateEditorconfig` run through `wsl-run` wrote `.editorconfig` on the Linux box, and the
next sync deleted it — beta is made to match alpha, so work done there is reverted and a diff taken
there proves nothing. A file a task generates has to arrive on the Mac: run the task with `LOCAL=1`,
or write the file here. `.editorconfig` is sborka's own, copied verbatim, which is what the task
writes and what `checkEditorconfig` compares against.

## Documentation

Format: [docs-bootstrap](https://github.com/youndie/docs-bootstrap). Documents in English, code in
English.

```bash
pip install pyyaml
LOCAL=1 make check      # the gate; CI runs exactly it
LOCAL=1 make report     # the two non-blocking reports
```

`code_anchors` reports most of this tree rotten today, and that is correct — the paths are where the
code will live, and the count going down is how the template arriving looks from here. It does not
become a gate when it reaches zero: a path quoted *as obsolete* is indistinguishable by machine from
a live one.

## Commits

English, Conventional Commits, no tool signature. `docs(research): …`, `feat(server): …`,
`build(server-jvm): …`, `ci: …`. Branch names the same way — `feat/item-store`, `fix/jvm-distribution`.
