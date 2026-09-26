---
id: endpoint-probes
title: Probes and version — kore's routes, as keel mounts them
type: api_endpoints
status: draft
services:
  - mostik-server
contract_source:
  - kore:kore-ktor io.github.youndie.kore.ktor.KoreRoutes
parent_feature: feature-shutdown-without-loss
---

# API: probes and version

> **Draft**, but the routes are verified: they are kore's, and keel mounts them unchanged. What is
> *target* is only that mostik keeps them as keel has them. Whether readiness should follow the broker is
> research open question 1; the table says what it does by default.

## Routes — all of them, no exceptions

| Method and path | Service | Auth tier | In the generated schema? | Purpose |
|---|---|---|---|---|
| `GET /health/startup` | mostik-server (kore) | none | no | has the process finished starting? A latch |
| `GET /health/ready` | mostik-server (kore) | none | no | should traffic come here now? `503` from the moment shutdown is announced. It does **not** follow the broker |
| `GET /health/live` | mostik-server (kore) | none | no | is the process wedged? |
| `GET /health` | mostik-server (kore) | none | no | an alias for **liveness**, not readiness; a proxy's health check must point at `/health/ready` |
| `GET /version` | mostik-server (kore) | none | no | which build this is |

## Handlers (code anchors)

| Route | Handler |
|---|---|
| the paths | `youndie/kore@47825a6!/kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/KoreRoutes.kt` |
| the three probes and `/health` | `youndie/kore@47825a6!/kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ProbeRoutes.kt` |
| `/version` | `youndie/kore@47825a6!/kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/VersionRoute.kt` |
| the `503` during shutdown, for every other path | `youndie/kore@47825a6!/kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ShutdownRefusal.kt` |
| where mostik mounts them | `server/src/commonMain/kotlin/io/github/youndie/mostik/Wiring.kt` |

## Errors

| Condition | Status | Body |
|---|---|---|
| any non-served path once shutdown has begun | `503` | `shutting down\n`, `Connection: close` |
