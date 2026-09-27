---
id: feature-shutdown-without-loss
title: Stop without making any answer false
type: feature
status: active
owner: unassigned
involved_services:
  - mostik-server
client_entries: []
api:
  - endpoint-records
  - endpoint-probes
tags: [shutdown, kore]
---

# Stop without making any answer false

## 1. Overview

On `SIGTERM`, mostik does what keel does through kore, with the producer as the last participant. It
announces that it is not ready, refuses new requests, lets the ones in flight finish within their
deadline, and closes the producer. The promise is not "nothing is lost": a `504` record may land or
not. The promise is that **no answer a client got becomes false**.

The drain-then-close order was already measured with kafkakn behind Ktor and kore (research §1.5). What
is new here is that the drain has to outlast the publish deadline.

## 2. Business rules

* A request that arrives after the signal gets `503` and is never sent, or its connection is refused. Measured
  under load in B-09: no `503` record in any topic in 40 rounds. Both builds answer kore's `503` through the whole
  announce and refuse connections only after it (`ci/b-12/run.sh`). The JVM build does so since B-12 switched off
  Ktor's own JVM shutdown hook; before that, it refused from 1 ms after the signal.
* A request in flight when the signal lands gets a real answer (`200`, `429` or `504`), and every answer is
  true: in B-09 no `200` was missing, and no `429` or `503` was present.
* **A reset connection is possible at one moment, and it costs nothing but its form.** A connection that arrives
  in the last few tens of milliseconds of the announce, just as the drain closes the listening socket, is reset by
  the kernel instead of answered. Measured in B-11: every reset started 4 to 43 ms before the listener closed and
  ended within 9 ms of it, in the window where the service was answering `503` anyway. None carried a record. A
  proxy that follows `/health/ready`, which is `503` for the whole announce, has stopped sending by then. A client
  that ignores readiness can meet one.
* `MOSTIK_DRAIN_MS ≥ MOSTIK_PUBLISH_DEADLINE_MS + 1 000 ms`, or the service refuses to start and names both
  values (research D6, B-07).
* The process ends within the grace period even when `close` cannot finish. kore cuts the release stage at
  3 s. With the broker stopped and records queued, measured three times per build: 8.06 to 8.09 s on native
  (`RELEASE_POOLS DEADLINE_EXCEEDED`, exit 0) and 5.05 to 5.12 s on the JVM (nothing was queued, exit 143),
  against a 30 s grace period (B-08). The 300 s `close` seen in B-04 is what `close` alone takes; the process
  does not wait for it.

## 3. Flow

1. `SIGTERM`. kore flips readiness (`/health/ready` → `503`) and waits `preDrainWait` (5 s by default).
2. The refusal starts: every path but the probes answers kore's `503`.
3. The engine drains for up to `MOSTIK_DRAIN_MS` (15 s by default, kore's own). **Not a ceiling:** while any
   connection is open, CIO spends it in full: 15.0 s at the default and 6.0 s at 6 000 ms, measured with one
   idle connection (B-07). Each request in it is bounded by the deadline
   (B-05).
4. `producer.close()` flushes and releases, as the `ShutdownParticipant`.

Steps 1, 2 and 4 are what both builds printed on `SIGTERM` in B-01: `ANNOUNCE` 5.0 s, then `DRAIN`, then
`RELEASE_POOLS`, where the producer is closed, with no request in flight.

## 4. Code anchors

| Service | Code |
|---|---|
| mostik-server | `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt` — the sequence, and the producer as its participant |
| mostik-server | `server/src/commonMain/kotlin/io/github/youndie/mostik/MostikConfig.kt` — `DRAIN_MS` and the drain-budget check (B-07) |
| the oracle | `ci/b-09/run.sh` — 64 clients' ledgers read against each round's topic (B-09) |

## 5. Scenarios (BDD / test cases)

The first was run by B-09, the second measured by B-08, and the third is built (B-07).

### Scenario: the drain covers the deadline
* **Given:** 64 concurrent publishers, each keeping a ledger of key → status, and a broker that is up
* **When:** `SIGTERM` lands at a random moment, in each of 20 rounds, on each build
* **Then:** every client got a status, except the few that arrived as the listening socket closed and were reset:
  1 to 9 per round in B-09's 64-client rounds, and 5 in 10 rounds after B-12 (B-11)
* **And:** every `200` is in the topic, and no `429` or `503` is; the `504`s are counted both ways
* *Run by hand with `ci/b-09/run.sh native|jvm 20`, 2026-09-27: zero disagreements in 40 rounds. The control
  (`… 3 control`) put 64 × `504` into every round, and on the JVM two of them landed. Reset connections were
  seen in 25 of the 40 rounds (B-11).*

### Scenario: broker gone at shutdown (built; measured, not automated)
* **Given:** the broker is stopped and requests are in flight
* **When:** `SIGTERM` lands
* **Then:** in-flight requests end as `504` on native and `429` on the JVM (see the publish feature's quirks)
* **And:** the process ends itself within the grace period: 8.1 s (native) and 5.1 s (JVM)
* **And:** the records queued for the `504`s are not in the topic after the broker is back: dropped with the
  process, not flushed
* *Measured by hand with `ci/b-08/run.sh native|jvm`, three rounds each, 2026-09-27. The requests had their
  `504` before the signal, since the 5 s announce outlasts the 3 s deadline, so "in flight" means records in
  the producer, not open requests.*

### Scenario: misconfigured budget
* **Given:** `MOSTIK_DRAIN_MS=3000` and `MOSTIK_PUBLISH_DEADLINE_MS=5000`
* **When:** the service starts
* **Then:** it exits `1`, and the message names `MOSTIK_PUBLISH_DEADLINE_MS` and the drain
* **Automated:** `MostikConfigTest::a drain shorter than the deadline plus its margin is refused and names both keys`;
  on both binaries on 2026-09-27: *"MOSTIK_DRAIN_MS (3000) must be at least MOSTIK_PUBLISH_DEADLINE_MS (5000) +
  1000 ms: a request in flight at SIGTERM would be cut before its answer"*, exit 1

## 6. Out of scope

* `SIGKILL`. A process killed outright runs no sequence; what it had in the producer is a `504` the
  client already has.

## 7. Quirks

* The `503` is kore's plain text, not mostik's JSON (research §1.6, consequence 3).
* A clean exit is `0` on native and `143` on the JVM. Assert that the process ended itself, never the
  code.
