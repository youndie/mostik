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

* A request that arrives after the refusal starts gets `503` and is never sent. This is kore's
  `installShutdownRefusal`, tested in kore; mostik's run of it under load is B-09.
* *Target, B-09:* a request in flight when the signal lands gets a real answer (`200`, `429` or `504`),
  never a reset connection.
* *Target, B-07:* `drain ≥ MOSTIK_PUBLISH_DEADLINE_MS + margin`, or the service refuses to start and names
  both values (research D6). **Not checked today.**
* *Target, B-08:* `close` ends within the grace period, or how long it takes is measured and routed to
  kafkakn. **Seen on native in B-04: 300 200 ms** for one record queued against an unreachable broker.

## 3. Flow

1. `SIGTERM`. kore flips readiness (`/health/ready` → `503`) and waits `preDrainWait` (5 s by default).
2. The refusal starts: every path but the probes answers kore's `503`.
3. The engine drains for up to `drain` (15 s by default). Each request in it is bounded by the deadline
   (*target*, B-05; today `send` is unbounded).
4. `producer.close()` flushes and releases, as the `ShutdownParticipant`.

Steps 1, 2 and 4 are what both builds printed on `SIGTERM` in B-01: `ANNOUNCE` 5.0 s, then `DRAIN`, then
`RELEASE_POOLS`, where the producer is closed, with no request in flight.

## 4. Code anchors

| Service | Code |
|---|---|
| mostik-server | `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt` — the sequence, and the producer as its participant |
| mostik-server | `server/src/commonMain/kotlin/io/github/youndie/mostik/MostikConfig.kt` — where B-07's drain-budget check goes |

The oracle for the scenarios below is B-09's client-side ledger read against the topic. It is not written yet.

## 5. Scenarios (BDD / test cases)

All *target*: B-09 runs the first two, and B-07 the third.

### Scenario: the drain covers the deadline
* **Given:** 64 concurrent publishers, each keeping a ledger of key → status, and a broker that is up
* **When:** `SIGTERM` lands at a random moment, in each of 20 rounds, on each build
* **Then:** every client got a status
* **And:** every `200` is in the topic, and no `429` or `503` is; the `504`s are counted both ways

### Scenario: broker gone at shutdown
* **Given:** the broker is stopped and requests are in flight
* **When:** `SIGTERM` lands
* **Then:** in-flight requests end as `504`
* **And:** the process ends itself within the grace period, or the time is recorded in B-08

### Scenario: misconfigured budget
* **Given:** a drain of 3 s and `MOSTIK_PUBLISH_DEADLINE_MS=5000`
* **When:** the service starts
* **Then:** it exits `1`, and the message names `MOSTIK_PUBLISH_DEADLINE_MS` and the drain

## 6. Out of scope

* `SIGKILL`. A process killed outright runs no sequence; what it had in the producer is a `504` the
  client already has.

## 7. Quirks

* The `503` is kore's plain text, not mostik's JSON (research §1.6, consequence 3).
* A clean exit is `0` on native and `143` on the JVM. Assert that the process ended itself, never the
  code.
