---
id: B-15
title: "A native publish whose record was written got an empty reply"
status: open
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
