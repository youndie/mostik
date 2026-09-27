---
id: B-15
title: "A native publish whose record was written got an empty reply"
status: question
priority: P1
size: M
stage: stage-4-shutdown
epic: feature-publish-over-http
---

# B-15 — a native publish whose record was written got an empty reply

Found by B-14's oracle on 2026-09-27: native, round 18 (`/tmp/mostik-b09-L8Sp` on the Linux box), client 64's 9th
request. Feature: [feature-publish-over-http](../features/feature-publish-over-http.md).

| key | status | curl | started | ended |
|---|---|---|---|---|
| `c64-8` | `200` | 0 | −6 848 ms | −6 809 ms |
| **`c64-9`** | **none** | **52, "Empty reply from server"** | −6 777 ms | −6 761 ms |
| `c64-10` | `200` | 0 | −6 735 ms | −6 704 ms |

Times are relative to `SIGTERM`, so this request was in **normal serving**, about 0.4 s into the load. It was
not at shutdown. **Its record is in the topic.** The service took the record, the broker acknowledged it, and the
client got a closed connection instead of `200`. A client that retries writes it twice. That is exactly what the
product exists to prevent. mostik's log for the round shows nothing: no exception and no warning.

It was one request in about 290 000 across 40 rounds and both builds. It is the only reset in B-09 and B-14 not at
the listener's close, and the only reset with a record behind it.

- **What is not known yet.** Whether the response was written and lost, or never written; which layer closed the
  connection (mostik's route, Ktor's CIO on native, the socket); whether it can happen on the JVM; and whether
  load, the shared build machine or the first second of a run makes it likelier.
- **The decision and its reason.** Reproduce before theorising: a run shaped like B-09's without the `SIGTERM`,
  long enough to expect several occurrences at the observed rate, on both builds. Log the handler's own "answer
  sent" alongside the ledger, so a record in the topic, an answer the handler sent and an empty reply can be told
  apart. Then find the layer.
- Not covered: fixing it before it reproduces. One occurrence is a lead, not a mechanism.

- AC: a reproduction rate per build, from a run long enough to make the rate meaningful, or a run that shows it
  does not recur, with that run's size stated.
- AC: if it recurs, the layer that closes the connection is named, with the evidence, and the fix or the upstream
  issue exists.
- Anchors: `ci/b-09/run.sh`, `server/src/commonMain/kotlin/io/github/youndie/mostik/publish/PublishRoutes.kt`.

## Iteration 1 (2026-09-27): reproduced, located below the route, and a question

All runs were on the Linux box with `ci/b-15/run.sh`: 64 clients in normal serving, no `SIGTERM` while they run, and
every request without an answer looked up in the topic.

| run | requests | no answer (curl 52) | of those, record written | the route had answered |
|---|---|---|---|---|
| native, 20 s | 20 790 | 1 | 1 | — |
| native, 600 s | 730 402 | 1 | 1 | — |
| JVM, 600 s | 746 799 | **0** | — | — |
| native, 1 200 s, temporary `ANSWERED <key>` printed after `call.respond` | 1 447 674 | 12 | 12 | **12 of 12** |
| native, 1 200 s, temporary fake `enqueue` (no Kafka send, 10 ms delay) | 836 639 | 14 | — (nothing sent) | — |

- **The route is not where the answer is lost.** For all 12 keys, `call.respond(...)` had returned and the line after
  it was printed. The client still got a closed connection with no bytes, 9 to 24 ms after it opened it.
- **kafkakn's send path is not either.** With the send replaced by a fake that answers after 10 ms, the empty replies
  stayed, at about 1 in 60 000. The producer was still constructed, its librdkafka threads connected and idle, and
  kore's refusal interceptor was in the pipeline, passing everything. Neither is excluded by this run.
- **The JVM build, the same code on the same CIO engine, the same curl and kernel, showed none in 746 799.** What is
  left is Ktor's CIO and `ktor-network` on Kotlin/Native, between the handler's `respond` and the socket.
- **The rate moves with timing.** It was about 1 in 375 000 plain, 1 in 120 000 with a `println` per request, and 1
  in 60 000 with the fake. So this is a race, not a fixed fraction.
- The two temporary patches were never committed, and the tree was checked clean after each.

**The question, for the owner.** mostik cannot fix an engine's internals, and the upstream is Ktor, which is
JetBrains: by the owner's rule, no LLM-made contribution goes there. So the item cannot meet its second criterion
alone. The choices:

1. **A minimal Ktor-only reproduction first:** a bare CIO server on `linuxX64`, no kore and no kafkakn, under the
   same curl load. If it reproduces, the owner files it with Ktor himself, with that repro and the numbers above.
2. **Accept it and say so:** the endpoint and feature documents already state what a client can meet ("no answer" is
   "unknown", like `504`). A client that retries on no answer can write twice, exactly as on a `504`.
3. **Prefer the JVM build where it matters,** since it showed none in 746 799. That is a deployment choice, and it
   gives up what the native build is for.

The recommendation is 1, then 2 while waiting: the repro turns "probably Ktor" into a fact, and the documents are
already true either way.

## Iteration 2 (2026-09-27): the owner chose 1, and the minimal reproduction is Ktor's alone

`ci/b-15/repro/` is a standalone build: Kotlin 2.4.20 and Ktor 3.6.0 CIO, with no kore, no kafkakn and no content
negotiation. It has one route: read the body, `delay(10)`, `respondText` a small JSON string. `load.sh` is the same
64-client load as `ci/b-15/run.sh`. The same source compiles for `linuxX64` and for the JVM.

| target | requests in 20 min | empty replies (curl 52) |
|---|---|---|
| linuxX64, run 1 | 1 575 338 | 11 |
| linuxX64, run 2 | 1 557 766 | 16 |
| linuxX64, run 3 (the load script below, exactly as written) | 1 639 097 | 13 |
| JVM, same source (Java 25) | 1 548 235 | **0** |

- **It is Ktor's CIO on Kotlin/Native, and nothing of mostik's.** Everything the first iteration could not exclude
  is absent here: kore's interceptor, kafkakn's idle producer, content negotiation. The JVM control of the same
  code shows none.
- The host was Ubuntu 24.04 on WSL2 (kernel 6.6.87.2), glibc 2.39, curl 8.5.0.
- **The upstream report is drafted for the owner to file**, because Ktor is JetBrains and no LLM-made contribution
  goes there. The draft carries the reproduction, the load as a copy-paste script (`ci/b-15/repro/issue-snippet.sh`,
  run as written before it was handed over: run 3) and the table above, and it names no project of this portfolio.
- **What stays true in mostik whatever Ktor does:** a client reads "no answer" as "unknown", the same as `504`. The
  endpoint and feature documents already say so.

**Still a question, now a narrower one:** the item's second criterion is met when the owner files the report.
Then this item records its address and closes.
