---
id: B-10
title: "A busy port stops the start-up with a sentence, not an abort"
status: open
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
- Anchors: `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt`.
