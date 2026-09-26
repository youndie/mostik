# CLAUDE.md — mostik

An HTTP → Kafka bridge whose status code is a true statement about the record: `200` is in the topic
at the returned offset, `429` and `503` are not in it, and `504` means nobody knows yet and a retry
may write it twice. Built from [keel](https://github.com/youndie/keel) (a server that ships twice: a
Kotlin/Native binary and a JVM distribution, one image) and publishing through
[kafkakn](https://github.com/youndie/kafkakn).

**State (2026-09-27): the route publishes, with no deadline yet.** `POST /topics/{topic}/records` answers
`200` with the offset the broker gave, checked by reading the topic on both builds (B-03). `send` is still
unbounded; the deadline is B-05, which waits on kafkakn B-76 (B-04). The repository exists only on this Mac —
it is not on GitHub yet; the Linux box has it as the mutagen session `mostik`. This sentence is dated so
that its age is visible; `backlog.md` and the build are what cannot go stale.

## How to start a session

1. [docs/research/research-architecture.md](docs/research/research-architecture.md) — what was read in
   kafkakn, keel, kore and the registry on 2026-09-26, and what follows. The three findings that most
   often contradict what the obvious implementation would do:
   - **`send` is already cancellable on both arms** (§1.1). `withTimeout` bounds the wait today; the
     question is what the answer means once it has.
   - **A cancelled `send` does not recall a queued record, and the caller cannot tell whether it was
     queued** (§1.2, §1.3). So an expired deadline is `504 outcome-unknown`, never `503`, and `429` is
     unreachable until kafkakn B-73/B-74 land (youndie/kafkakn#94) and mostik's B-04 takes them.
   - **The drain must outlast the publish deadline** (§1.6), or a request in flight at `SIGTERM` gets a
     reset connection instead of an answer.
2. [backlog.md](backlog.md) — the goal, the stages, the index. Items are one file each in
   `docs/backlog/`; the index between the markers is generated, so edit the item and run
   `python3 scripts/backlog_index.py`.
3. The layer document the task belongs to: [docs/services/mostik-server.md](docs/services/mostik-server.md)
   for how it is built and its quirks, [docs/api/endpoint-records.md](docs/api/endpoint-records.md) for the
   route, [docs/features/feature-publish-over-http.md](docs/features/feature-publish-over-http.md) for the
   scenarios that are the acceptance. What is not built yet is marked *target* in each. The map is
   [docs/README.md](docs/README.md).
4. The skills, when the task is building rather than documenting: `ktor-server-feature` for the route,
   `kmp-testing` for the suites, `native-service-bootstrap` for the skeleton, `backlog-item` for an
   item. The repository is read **before** the skill.

## Where a line goes

mostik is a product, not a template, so its own code belongs here. What does not:

| the line was | it goes to |
|---|---|
| a build flag, a linker option, a CI step | [sborka](https://github.com/youndie/sborka) |
| lifecycle, probes, config, shutdown | [kore](https://github.com/youndie/kore) |
| what a producer call means, how long it waits, what it throws | [kafkakn](https://github.com/youndie/kafkakn) — research D7 |
| wiring every service from keel would need | [keel](https://github.com/youndie/keel) |

## The loop merges its own pull requests

**While the repository is local only (the owner's choice, 2026-09-27), the loop merges locally.** There
is no remote, no pull request and no CI. An item is handed over like this:

1. one branch per item, `feat/b-<nn>-<slug>`, and the first commit sets the status to `wip`;
2. the local gate, the same commands CI's jobs run: `LOCAL=1 make check` on the Mac, and
   `~/.claude/bin/wsl-run make build` on the Linux box, with the test-result XML read rather than the log;
3. green → rebase on `main`, regenerate the index, `git merge --ff-only` into `main`, delete the branch;
4. the item's findings record where each check ran, because no pull request body holds it.

A `question` item is never merged into being decided, here as below. The rules below take over the day
the repository is on GitHub.

*The rules for GitHub, inherited from keel:*

**A `/loop` iteration merges the pull request it opened, once CI is green.** One item, one branch, one
pull request, merged by the loop — `gh pr merge --rebase --delete-branch`.

This is a deliberate trade and it is worth naming rather than discovering. What is given up is
review: nobody reads the diff before it is on `main`. What is bought is a loop that runs unattended,
and without it the loop stops after one item — statuses only change on `main` when a pull request
merges, and an item whose blocker still reads `open` is not pickable. keel's first iteration hit exactly
that wall.

The conditions, which are not negotiable inside an iteration:

- **Green CI, read from the pull request, before merging.** Not `make check` locally — the run on the
  head commit. A merge on a pending or failing run is the whole guard gone.
- **The pull request body still carries the acceptance checklist**, ticked from evidence. The body is
  what a person reads afterwards instead of the diff, so it is the review surface and it is written
  as one.
- **A `question` item is never merged into being decided.** It goes to `main` as a `question`, and
  the loop stops there.
- **Anything routed out of mostik — kafkakn, sborka, kore, keel — is filed before the merge**, not after.
  A finding that exists only in a merged commit message is a finding nobody will act on.
- **`--rebase`, not squash.** The commit messages carry the reasoning; a squash collapses them into
  the pull request title and the *why* is what survives longest.

Not covered by this: a pull request a person opened. The loop merges what the loop opened.

## The two rules

- **`main` describes what exists.** A layer document is `draft` on its branch and goes `active` only
  after it is **re-read against the code**, not flipped. In keel that re-reading found an instruction
  that ran and silently produced the wrong artefact. `docs_check.py --on-main` is the mechanical half,
  and it is on in CI's push job.
- **What was verified is separated from what was assumed, explicitly.** Everything in research §1
  carries a file, a coordinate or a URL with the date it was read. Everything else says "decision" or
  "hypothesis", and a hypothesis names the item that settles it. A document that blurs the two is a
  defect worth an item, because both halves then look equally authoritative.

## Rules that are cheap to follow and expensive to discover

Most of these are kore's and sborka's, restated because a session here will not have their
repositories open. The full list with addresses is keel's, at
`youndie/keel@6be238d!/docs/services/keel-server.md` §8.

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

This repository is a mutagen session (one-way replica, alpha here, beta `mostik` on the Linux box,
created 2026-09-26 with keel's ignores: `build`, `.gradle`, `.kotlin`, `.idea`, `.DS_Store`, VCS).
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

`code_anchors` reports the addresses inside kafkakn, keel and kore in their own section and never
counts them as rot.

## Commits

English, Conventional Commits, no tool signature. `docs(research): …`, `feat(server): …`,
`build(server-jvm): …`, `ci: …`. Branch names the same way — `feat/publish-route`, `fix/drain-budget`.
