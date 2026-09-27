---
id: B-10
title: "A busy port stops the start-up with a sentence, not an abort"
status: done
priority: P2
size: S
stage: stage-1-skeleton
epic: feature-shutdown-without-loss
---

# B-10 — a busy port stops the start-up with a sentence, not an abort

Found in B-07 on 2026-09-27, by a test harness that reused a port too soon. The native release binary,
started on a port another process holds, logs `Uncaught Kotlin exception: …JobCancellationException…`,
caused by `PosixException.AddressAlreadyInUseException: EADDRINUSE (98)`. It then dies with `SIGABRT`: exit
134, and a core dump where the box allows one. The bind happens in CIO's accept coroutine after
`server.start(wait = false)` has returned, so no code of mostik's stands between the failure and the runtime's
handler for an uncaught exception. Feature: [feature-shutdown-without-loss](../features/feature-shutdown-without-loss.md).

What an operator sees is a stack trace and a crash, for the most ordinary misconfiguration there is. Every
other start-up refusal in this service is one sentence and exit 1 (`CLAUDE.md`, *How to start a session*; the
refusals of B-01, B-05 and B-07).

- **The decision and its reason.** The start-up waits until the engine is actually listening, or has failed to,
  before it declares itself started. A failure to bind is printed as one sentence naming `MOSTIK_PORT`, and the
  process exits 1 on both builds. The JVM build's behaviour is not measured yet, and this item measures it first.
- **Where the fix belongs is part of the item.** The wiring is keel's. If the change is general (any service from
  the template has it), it goes to keel as an issue before the merge (`CLAUDE.md`, *Where a line goes*). A local
  fix carries that issue's address.
- Not covered: anything else that fails inside the engine after start-up.

- AC: on both builds, starting on a port another process holds exits 1, prints a sentence that names the port
  and `MOSTIK_PORT`, and leaves no core dump.
- AC: the keel issue exists, or the item says why the defect is mostik's alone.
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/mostik/MostikMain.kt`, `ci/b-10/run.sh`.

## Findings (2026-09-27)

`ci/b-10/run.sh native|jvm` holds the port with a listening socket that ends by itself. It is not `nc -l`,
because of B-07's harness finding.

- **Measured first, before the change:**
  - native: exit 134 (`SIGABRT`), 53 to 59 lines of stack starting `Uncaught Kotlin exception`;
  - JVM: exit 1, 18 lines of stack starting `Exception in thread "main"`.

  Neither said `MOSTIK_PORT`.
- **The change:** `portProblem(port)` binds `MOSTIK_PORT` once with `ktor-network` and closes it before
  `startMostik`. A failure is `refuse(...)`, the same path every other start-up refusal takes. It narrows the
  problem and does not close it: something else can take the port between the check and the server's bind. The
  root is Ktor's CIO, and no issue goes to JetBrains from here.
- **AC: exit 1, one sentence naming the port and `MOSTIK_PORT`, no core dump, on both builds.**
  - native: `MOSTIK_PORT (18105) cannot be listened on: EADDRINUSE (98): Address already in use`, exit 1;
  - JVM: `MOSTIK_PORT (18105) cannot be listened on: Address already in use`, exit 1.
- **Not broken by it:** a normal start binds right after the check releases the port. `ci/b-03/run.sh` and
  `ci/b-12/run.sh` still pass on both builds.
- **AC: the keel issue exists:** youndie/keel#49. The wiring that aborted is keel's unchanged, so every service
  from the template has it.
- **Suites:** 30 tests on `jvm` and 30 on `linuxX64`, none failed. `PortProblemTest` binds a port and checks the
  sentence, and its positive control releases the port and checks it passes. Mutant (`portProblem` answers
  `null`): killed by `a port another socket holds is named with MOSTIK_PORT`.
- **Found by the linter on the way:** the first version caught `Exception` around the bind, which would have
  swallowed a `CancellationException`. The portfolio's ktlint rule refused it, and the catch now rethrows
  cancellation first.
