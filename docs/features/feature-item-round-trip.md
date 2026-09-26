---
id: feature-item-round-trip
title: One item, stored and returned, on both targets
type: feature
status: active
owner: unassigned
involved_services:
  - keel-server
client_entries: []
api:
  - endpoint-items
tags: [template, parity]
---

# One item, stored and returned, on both targets

## 1. Overview

keel's only feature, and it is not about items. `POST /items` then `GET /items` is the shortest path
that touches every mechanism a new service needs working on day one: JSON in and out, a store that is
a real database on both targets, a route that survives a shutdown mid-request, and a binary that
answers the same way whether it was compiled to a JVM class file or to an ELF. The entity is
`Item { id, name }` and it stays that way — a starter whose example grows features stops being a
starter.

`client_entries: []` is the answer rather than an omission: keel has no client, by the brief's first
non-goal. A client template is a separate repository.

The scenarios below **are** the acceptance criteria of the template, and six of them restate the
brief's declared acceptance in a form a test can hold.

Eight of seventeen carry an `**Automated:**` line. Of the rest, several are **verified without being
automated** and say which run did it — the oracle for the shutdown, the stand for the numbers, a
container for the image — and one, the `scratch` page, has nothing to run against at all because
[B-16](../backlog/B-16-static-image.md) decided not to ship that image. The missing line is the honest
signal in every case: `bdd_report.py` counts each of them as manual, and it is right to.

## 2. Business rules

* An item has exactly two fields. Adding a third is the brief's red list: "a second entity" and a
  growing example are the same failure at different sizes.
* The store is reached through `ItemStore`, always. The route never touches a driver, so the route's
  tests run against an in-memory implementation and say nothing about SQLite.
* **Exactly one sqlx4k driver is on the classpath.** Adding a second is a link failure on native, not
  a resolution failure — a clone that wants Postgres replaces SQLite rather than adding to it.
* A request in flight when `SIGTERM` arrives is finished, not dropped. A request arriving after the
  announce gets `503` and `Connection: close`.
* Both targets answer identically, except for what the parity normaliser declares (the exit code, the
  `Server` and `Date` headers, and `/version`'s build time). The normaliser is written **before** the
  first parity run, so that it cannot become a list of whatever happened to differ.
* A number that was not measured says so. The README carries the image sizes and the clone-to-ready
  time with the date they were taken; every other figure is a hypothesis and is labelled one.

## 3. Flow

```
POST /items ──▶ ItemRoutes ──▶ ItemStore ──▶ sqlx4k-sqlite ──▶ SQLite file
                                                 │
                            native: the Rust driver · JVM: org.xerial:sqlite-jdbc
GET  /items ──▶ ItemRoutes ──▶ ItemStore ──▶ … ──▶ [Item, …] as JSON

SIGTERM ─▶ kore: announce (readiness 503) ─▶ drain (in-flight finishes)
                                    ─▶ release (the store closes) ─▶ exit
```

There are no cross-service calls and no auth tiers, because there is one service and no
authentication (see [endpoint-items](../api/endpoint-items.md)).

## 4. Code anchors

| Service | Code |
|---|---|
| keel-server | `server/src/commonMain/kotlin/.../item/` — the whole feature: `Item`, `ItemStore`, `ItemRoutes` |
| keel-server | `server/src/commonMain/kotlin/.../Wiring.kt` — where the routes, the probes and the shutdown registrations meet |
| keel-server | `server/src/commonTest/kotlin/.../item/ItemRoutesTest.kt` — the route against an in-memory `ItemStore` |
| keel-server | `server/src/commonTest/kotlin/.../item/ItemStoreContractTest.kt` — the same suite run against SQLite on both targets |
| keel-server | `k6/items.js` — the scenario both binaries are driven with, for parity and for `:server:measure` |
| kore | `kore/samples/service/src/commonMain/kotlin/io/github/youndie/kore/sample/KoreWiring.kt` — the wiring keel's is derived from |

## 5. Scenarios (BDD / test cases)

A scenario carries an `**Automated:**` line when a test exercises it **as written**; the rest are
target behaviour, and the absence is the honest signal. Eight of seventeen are automated — each on
the JVM and on `linuxX64` from one source, which is the property worth having rather than the count.

### Scenario: an item survives the round trip, on both targets

* **Given:** a freshly started keel binary with an empty store
* **When:** `POST /items` with `{"id":"a","name":"first"}` and then `GET /items`
* **Then:** the `POST` answers `201` with the stored item and the `GET` answers `200` with an array
  containing exactly it
* **And:** the same two calls against the other target produce byte-identical bodies

### Scenario: the route renders a body rather than only a status

* **Given:** the module with no store behind the route yet
* **When:** `GET /items` is called
* **Then:** the body is the exact JSON, not merely a `200`
* **And:** this is asserted on both targets, because every rendered byte on Kotlin/Native goes
  through glibc `iconv` — a status code crosses no charset, which is how a `401` from a static image
  was once read as a pass
* **Automated:** `ItemRoutesTest`

### Scenario: the store is the same code on both targets

* **Given:** the `ItemStore` contract suite
* **When:** it is run as `jvmTest` and as `linuxX64Test`
* **Then:** both run the same scenarios against a real SQLite file, and **both report a non-zero
  count** — a suite that ran nothing exits zero and must not pass
* **And:** one `commonMain` implementation satisfies both, which is what settled D1: the brief's
  second implementation was never needed
* **Automated:** `ItemStoreContractTest`

### Scenario: the rows are on disk and survive a restart

* **Given:** an item written through the store
* **When:** the driver is closed and another is opened on the same file
* **Then:** the item is there
* **And:** this is the one case the rest of the suite cannot be: writing and reading through the same
  open driver passes just as well against a database that exists only in that process, which the
  service shipped for the length of one build — every request correct, everything gone on restart
* **Automated:** `ItemStoreContractTest`

### Scenario: `kill -TERM` under load finishes every in-flight request

* **Given:** the binary under load with requests in flight
* **When:** the container receives `SIGTERM`
* **Then:** every request in flight at the signal receives its response
* **And:** requests arriving after the announce receive `503` with `Connection: close`
* **And:** the process ends itself — `0` on native, `143` on the JVM — and is not `SIGKILL`ed (`137`)
* **And:** the run is **inconclusive**, not green, if fewer than the declared floor of requests were
  in flight at the signal
* **Verified on both halves** by kore's oracle — the native image in
  [B-07](../backlog/B-07-shutdown-oracle.md) and the JVM distribution in
  [B-19](../backlog/B-19-oracle-on-the-jvm-half.md). Seven passes each, nothing failed: 32 of 32
  spanning the signal all completed on both, every refusal carrying `Connection: close`, exit `0`
  native and `143` JVM
* **The asymmetry is closed**, and it mattered: `EmbeddedServer.stop` runs its steps in the opposite
  order on the two platforms, so the JVM half was where a defect could hide from a green native run.
  Not automated — nothing runs the oracle on a build

### Scenario: readiness goes false before the drain starts

* **Given:** a running binary answering `200` on `/health/ready`
* **When:** `SIGTERM` arrives
* **Then:** `/health/ready` answers `503` while the socket is still accepting, and only then does the
  drain begin
* **And:** new arrivals get `503` for the whole announce window rather than being refused at the
  socket
* **And:** nothing is closed in `ApplicationStopping`, which runs on the wrong side of the drain on
  one of the two platforms
* **Observed** against the image in [B-07](../backlog/B-07-shutdown-oracle.md) — `503` from 0.0 s to
  4.6 s, connection refused from 5.1 s, exit `0`. Not automated: kore's oracle cannot yet be pointed
  at keel ([kore#81](https://github.com/youndie/kore/issues/81))

### Scenario: a missing required variable stops the process instead of a route

* **Given:** an environment with `KEEL_DB_PATH` unset
* **When:** the configuration is read
* **Then:** it refuses, and reports **every** problem it found rather than the first one — a process
  that fails one variable at a time costs one restart each, and a deployment being configured for the
  first time has several
* **Automated:** `KeelConfigTest`

### Scenario: `--print-config` answers without starting the process

* **Given:** the same unusable environment
* **When:** the binary is run with `--print-config`
* **Then:** it prints every value with its origin and exits with the verdict the start would have
  given, **without** starting — because it is asked precisely when the process will not start

### Scenario: a misspelled variable is named rather than ignored

* **Given:** `KEEL_DB_PATHS` set where the schema declares `KEEL_DB_PATH`
* **When:** the configuration is read on a target that can enumerate the environment
* **Then:** it refuses, naming the declared variable the unknown one is probably a misspelling of
* **And:** where the environment **cannot** be enumerated the same variable is passed over in
  silence, because reporting "no unknown variables" on a target that never looked is a deployment
  reading an absent check as evidence
* **Automated:** `KeelConfigTest`

### Scenario: a secret is masked wherever the configuration is rendered

* **Given:** `KEEL_TRACY_KEY` set
* **When:** the configuration is rendered
* **Then:** the value does not appear — masking follows the declaration rather than a list of names
  somebody keeps in sync with the schema
* **Automated:** `KeelConfigTest`

### Scenario: `/health` is an alias for liveness and not for readiness

* **Given:** a running module
* **When:** `/health` and `/health/live` are both called
* **Then:** they answer with the same status and the same body
* **And:** that is the point rather than a detail: a chart pointing its readiness probe at `/health`
  gets a probe that cannot fail while the process is alive, which is what three separate probes exist
  to replace
* **Automated:** `ItemRoutesTest`

### Scenario: the startup probe is a latch, and means nothing until a gate is named

* **Given:** a `StartupGate` with no named gates — which is what keel ships
* **When:** `/health/startup` is called
* **Then:** it answers `200` immediately, because a gate with nothing outstanding is started from
  birth
* **And:** with a named gate it answers `503` naming what is outstanding, and `200` once that gate
  completes — never `503` again afterwards
* **Automated:** `ItemRoutesTest`

### Scenario: the image starts with the binary and nothing beside it

* **Given:** the image built from the committed `Dockerfile` with no `COPY` line other than the binary
* **When:** the container is started
* **Then:** it answers `/health/ready` — no `cannot open shared object file`, no library dragged from
  the builder stage
* **And:** `POST` then `GET /items` returns a body with non-ASCII intact, because a status code
  crosses no charset
* **And:** `docker stop` ends it with exit code `0` and kore's transcript in the logs
* **And:** the image is under the declared budget for its base — 13 972 497 bytes against 25 MB,
  measured with the method named in [B-04](../backlog/B-04-image-and-size-budget.md)

### Scenario: the static image serves a rendered page, not a status code — *not shipped*

**keel builds no static image and will not**, so this scenario has nothing to run against and no item
waiting to inherit it — B-18 is `dropped`. It is kept for the reason it was worded this way, which
outlives the decision:

* **Given:** a `scratch` image, if one is ever built
* **When:** `POST /items` and then `GET /items` are called against it
* **Then:** the JSON body comes back correct — **not** merely a `2xx`
* **And:** no `Failed to open iconv for charset UTF-8 with error code 22` appears in the log

A `401` from a static image was once read as a pass: a `401` is produced before any text crosses a
charset, and every rendered byte goes through glibc `iconv`, which is `dlopen`ed. **That is the
transferable half** — it is why the ordinary image's smoke asserts a rendered body, and it would be
true of any image keel ever built. [B-16](../backlog/B-16-static-image.md) is why there is no static
one.

### Scenario: parity — the same k6 scenario, no diff after the normaliser

* **Given:** the JVM distribution and the native binary, both serving
* **When:** `k6/items.js` runs against each
* **Then:** the recorded responses are identical after the declared normaliser, and the normaliser is
  the file that existed before the run
* **And:** the comparison refuses to pass on nothing — it asserts both sides recorded the same
  non-zero number of responses first, because the first run of this scenario diffed two empty files
  and reported success
* **Observed** in [B-05](../backlog/B-05-parity-smoke.md): 160 checks passed on each target, three
  responses recorded on each, `diff` clean. Not automated — nothing runs it on a build yet

### Scenario: the clone builds on a machine that has never seen the portfolio

* **Given:** a clone of the template with the portfolio's Maven repository configured and no other
  local state
* **When:** both targets are built, `check` is run, the image is built
* **Then:** all three succeed and `/health/ready` answers on both binaries
* **And:** the wall time — including the Kotlin/Native toolchain fetch — is recorded in the README
  with the date it was taken, whatever it turns out to be
* **Measured** in [B-08](../backlog/B-08-clone-to-ready.md): **3 min 48 s**, of which the build is
  82 %. The image build is excluded and says so — the timing container has no Docker

### Scenario: the template stays a template

* **Given:** the repository at any commit
* **When:** the line counts are taken
* **Then:** Kotlin under `server/` is under 500 lines and Gradle **build logic** across the repository
  is under 100
* **Measured** 2026-09-16: Kotlin 214 main-source lines, build logic **95 of 100**. What the second
  counts was decided under pressure in [B-03](../backlog/B-03-jvm-half-ships.md) — the version catalog
  is excluded — and `backlog.md` carries that argument, including the part where a criterion was
  edited by the work it was constraining
* **And:** going over either is treated as the signal that something belongs in sborka or kore, and
  the finding is filed there rather than fixed here

## 6. Out of scope

* Authentication, a chart, a client, a second entity, a second route group.
* Migrations beyond the one statement that creates the table. A migration framework is a decision a
  real service makes.
* Metrics and tracing: `kore-observability` is a commented block, because it is portfolio-only and a
  clone outside the portfolio cannot resolve it.
* The "stranger test" — a clone with **no** portfolio repository configured — which waits for kore and
  sborka to reach Maven Central and is not keel's work.

## 7. Quirks

The platform divergences that make these scenarios read strangely are in
[keel-server](../services/keel-server.md) §8, all thirteen of them. The three that bear directly on
the scenarios above:

* **Exit codes differ between the targets on a *correct* shutdown** — `0` and `143`. The scenario
  asserts "not `137`" for that reason.
* **`Connection: close` does not close the socket** on CIO; it is a header promise. A scenario that
  asserted the socket closed would fail against correct behaviour.
* **A suite that ran zero tests exits zero**, and on one host `linuxArm64Test` is never created at
  all. "Both report a non-zero count" is in the second scenario because of that, not as pedantry.
