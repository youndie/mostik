---
id: endpoint-probes
title: Probes and version — kore's routes, as keel mounts them
type: api_endpoints
status: active
services:
  - mostik-server
contract_source:
  - kore:kore-ktor io.github.youndie.kore.ktor.KoreRoutes
parent_feature: feature-shutdown-without-loss
---

# API: probes and version

> The routes are kore's, mounted by `mostikModule` as keel mounts them; `/health/ready` and `/version` were
> answered by both builds on 2026-09-27 (B-01). Whether readiness should follow the broker is research open
> question 1; the table says what it does today.

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
| any non-served path once the drain has begun (kore `0.1.7`; under `0.1.6`, from the announce) | `503` | `shutting down\n`, `Connection: close` |
