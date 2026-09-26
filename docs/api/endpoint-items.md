---
id: endpoint-items
title: keel — every route the template serves
type: api_endpoints
status: active
services:
  - keel-server
contract_source:
  - "keel:server Item"
parent_feature: feature-item-round-trip
---

# API: keel's complete route table

> **The complete route reference** — keel's own two routes and the four kore mounts, because a
> starter's route table is the thing a clone edits first and a route it does not know about is a
> route it will collide with. Bodies live in the contract class named in `contract_source`; this
> document gives paths, statuses and the things that are not obvious.
>
> **All seven routes answer.** keel's two go through a real SQLite database since B-02; kore's five
> are mounted from `kore-ktor` and the rows below say which source each was read in. What is still
> *target* is the error table's first row — nothing has yet sent a malformed body and written down
> what came back.

## Routes — all of them, no exceptions

| Method and path | Auth tier | Mounted by | In a generated schema? | Purpose |
|---|---|---|---|---|
| `GET /items` | none | keel | no — keel generates no schema | every item, ordered by id, as a JSON array |
| `POST /items` | none | keel | no | create one item from a JSON body; answers `201` with what was stored |
| `GET /health/startup` | none | kore, `installKoreProbes` | no | has the process finished starting? A **latch**: once `200`, never `503` again |
| `GET /health/ready` | none | kore, `installKoreProbes` | no | should traffic be sent here *right now*? |
| `GET /health/live` | none | kore, `installKoreProbes` | no | is the process wedged and in need of a restart? |
| `GET /health` | none | kore, `installKoreProbes` | no | **alias for liveness**, not readiness — see the warning below |
| `GET /version` | none | kore, `installKoreVersion` | no | which build this is |

**There is no authentication anywhere, deliberately.** keel ships none rather than shipping half of
one, because a starter's half-authentication is what a clone inherits without reading. A real one
comes from the `ktor-server-feature` skill.

**`/health` is liveness.** A chart that points its readiness probe at `/health` gets a probe that
cannot fail while the process is alive — which is precisely what three probes exist to stop. The
alias is there because every chart in this portfolio already names it, and removing it would break a
running deployment. Verified in
`kore/kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ProbeRoutes.kt`.

## Handlers (code anchors)

| Route | Handler |
|---|---|
| `GET /items`, `POST /items` | `server/src/commonMain/kotlin/.../item/ItemRoutes.kt` |
| the store behind both | `server/src/commonMain/kotlin/.../item/ItemStore.kt` |
| the three probes and `/health` | `kore/kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ProbeRoutes.kt` |
| `/version` | `kore/kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/VersionRoute.kt` |
| the 503-during-shutdown interceptor | `kore/kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ShutdownRefusal.kt` |

## Request and response bodies

`Item` is a `@Serializable` data class in `server/src/commonMain/kotlin/.../item/Item.kt` — two
fields, and they are not listed here. A copied field list is stale within a sprint; the path is not,
and there is a check for the path.

`POST /items` takes one `Item` and answers with the stored one. `GET /items` answers with an array.
`Content-Type: application/json` on both, via `kotlinx.serialization`.

## Errors

| Condition | Status | Body | Note |
|---|---|---|---|
| a malformed or unparseable JSON body on `POST` | `400` | the serialization failure as text | *target*; the exact string is whatever `kotlinx.serialization` produces and is written down here after the first run, not guessed |
| `POST /items` with an id that is already stored | `500`, unhandled — the constraint violation propagates | the driver's own message, and **the two differ** | **a quirk rather than a contract**, and the wording is measured rather than assumed. Native: `[Database] :: [1555] (code: 1555) UNIQUE constraint failed: items.id`. JVM: `[Database] :: [Database] :: [SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed (UNIQUE constraint failed: items.id)`. keel asserts that it fails, never what it says. A clone wanting a `409` writes it; a template that invented one would be teaching an error model it had not thought about, and the parity normaliser deliberately does **not** hide this — see B-05 |
| readiness has not been reached | `503` on `/health/ready` | the failing check, **with the age of its answer** | kore: a stale healthy answer and a fresh one are different facts |
| startup has not completed | `503` on `/health/startup` | `starting — waiting for: <names>` | kore, read in `ProbeRoutes.kt` |
| the process is wedged | `503` on `/health/live` | `wedged — <reason>` | kore, read in `ProbeRoutes.kt` |
| any request arriving after the shutdown announce | `503` + `Connection: close` | — | kore's `installShutdownRefusal`, mounted **before** the probes so it cannot miss the first request after the announce |
| `KORE_VERSION_REDUCED` is on and the release still names the commit | the process **refuses to start** | the reason, at startup | `VersionRoute.kt` — a `404` would be indistinguishable from a broken deployment |

**`Connection: close` is a promise about the header, not about the socket.** CIO reads keep-alive
from the *request's* header, so the connection may well stay open. kore documents this as a promise it
does not make, and keel repeats it here because a smoke test that asserts the socket closed will fail
against correct behaviour.

## What this document deliberately does not cover

* **No generated schema.** keel mounts no OpenAPI. A generated schema needs a running service and
  credentials, which is exactly what a reviewing agent does not have, and this table is the answer to
  that — not a supplement to something else.
* **`/metrics`.** Not mounted. `kore-observability` wires tracy, metrik and katcher in one call and is
  a commented block in keel, because it is portfolio-only and a clone outside the portfolio cannot
  resolve it.
