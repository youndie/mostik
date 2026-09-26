# Technical brief: an HTTP → Kafka bridge on kafkakn

| | |
|---|---|
| Date | 2026-09-26 |
| Repository | new — working name `mostik` (§10), created from the `keel` template, public |
| Platforms | server only: `linuxX64` native binary **and** the JVM distribution, one image — as keel ships. No client, no screens |
| Stack | Kotlin Multiplatform `server` (Ktor CIO, kore), `distribution` (JVM), producer = `kafkakn-core` — *decided*, see §8 |
| Documentation | `docs/` inside the repository, docs-bootstrap format, in English like keel and kafkakn (§8) |
| Status of this document | a branch artefact under `research/`; deleted before the branch merges |

How the sections become documentation: §8 and §10 are the research document; §3 feeds the
features' business rules; §4 → `features/`, §6 → `api/`, §7 → `services/` are drafted in a pull
request that stays open until the code gives them anchors; §9 becomes the backlog. There is no §5
layer: the service has no screens, so there is **no design brief**.

## 1. Problem and audience

A service that can speak HTTP but not Kafka wants to put a record on a topic and be told the
truth about it: *written*, *not written, try again*, or *nobody knows yet*. Today the usual
bridge answers `200` once the record is queued locally, and loses it if the broker never
acknowledges. It either waits forever or cuts the wait and answers `503` for a record that lands
anyway.

The audience is two things at once. One is an operator running it behind a reverse proxy. The
other is **kafkakn itself**. The bridge is the first consumer whose HTTP response is bound to a
broker acknowledgement *under a deadline*. That makes it the first to ask the library what a
cancelled `send` means. The library answers that today only in KDoc (§8, rows K1–K4). The
shutdown half is **not** new ground: kafkakn research §2.14 already measured drain-then-close
through xyk (0 lost of 91 149 accepted, 20 SIGTERMs). The bridge re-proves it on keel's wiring
and adds one question: how the drain budget interacts with the publish deadline.

## 2. Scope

**In the first version:**

- publish one record per HTTP request to an allowlisted topic, and answer only after the broker's
  acknowledgement, with its partition and offset
- a publish deadline: past it the answer is `429` when the record was provably never queued, and
  `504 outcome-unknown` when it was
- an ordered shutdown through kore: stop being ready, refuse new requests, drain in-flight
  requests within their deadline, then close the producer
- both builds (native and JVM) pass the same scenarios against the same broker
- the kafkakn changes this forces, filed and done **in kafkakn** (own library, §9 stage 3)

**Out, on purpose:**

- authentication — the reverse proxy does it; the service trusts its network (user, 2026-09-26)
- idempotency keys / deduplication — `504` says "unknown" and tells the caller a retry is unsafe; dedup is the reader's business (user, 2026-09-26)
- batching endpoints, JSON envelopes, schema registry, tombstones over HTTP — nobody asked for them
- the reverse direction (Kafka → HTTP), consumers, groups
- an outbox / local persistence — then the deadline question disappears, and the question is the reason this service exists
- Maven Central — kafkakn is in reposilite only, by the owner's decision

## 3. Domain

| Entity | Identified by | Owned by / tenant | Notes |
|---|---|---|---|
| `Topic` | name, from the allowlist in configuration | the deployment | a name outside the allowlist does not exist (`404`) |
| `PublishRequest` | none — one HTTP request | the caller | value = request body bytes verbatim; key and record headers from HTTP headers (§6) |
| `Outcome` | closed set: `Acknowledged`, `NotQueued`, `Unknown`, `Rejected`, `ShuttingDown` | — | the one thing the response reports; each maps to exactly one status (§6) |
| `PublishDeadline` | configuration, milliseconds | the deployment | one value for the service in v1; must be below the proxy's upstream timeout |

Tenancy: **none**. One deployment, one producer, one allowlist. There is no role column.

## 4. Features

### feature-publish-over-http: publish a record and report its real outcome

**Overview.** A `POST` puts one record on an allowlisted topic. The response waits for the
broker's acknowledgement or for the deadline, whichever comes first. The status code tells the
caller what happened to the record, not what happened to the request.

**Business rules:**

- `200` is sent **only** after `send` returned `RecordMetadata` — never after enqueue
- a record the service answered `429` or `503` for is **never** in the topic
- a record the service answered `504 outcome-unknown` for may or may not be in the topic, and the body says a retry may duplicate it
- every request ends within `PublishDeadline` + a fixed margin; no request waits out `message.timeout.ms`
- an unknown topic is refused before anything touches the producer

**Modules:** `server`, `distribution`. **Screens:** none. **Endpoints:** endpoint-records.

**Scenarios (target):**

- *acknowledged* — Given topic `orders` is allowlisted and the broker is up, when a client posts
  the §5a order, then it gets `200` with `partition` and `offset`, and the topic holds exactly
  that value at that offset.
- *queue full, never queued* — Given the producer's queue is at its bound and stays there past
  the deadline, when a client posts, then it gets `429` with `Retry-After`, and the record is not
  in the topic after the queue drains.
- *queued, broker silent* — Given the broker is paused (`docker pause`) after the record is
  queued, when the deadline expires, then the client gets `504` with `outcome: unknown`. After
  the broker is resumed, the record **is** found in the topic. That is the scenario that proves
  the word "unknown" was needed.
- *topic not allowlisted* — when a client posts to `audit`, then `404 topic-not-found`, and the
  producer's metrics show no send.
- *oversized body* — when the body exceeds `MAX_RECORD_BYTES`, then `413` before any send.
- *two builds, one answer* — every scenario above gives the same status on the native binary and
  on the JVM distribution.

### feature-shutdown-without-loss: stop without lying about what was written

**Overview.** On `SIGTERM` the service does what keel already does through kore, with the
producer as the last participant. It announces not-ready, refuses new requests, drains the ones
in flight (each bounded by its own deadline) and closes the producer.

**Business rules:**

- a request that arrives after the refusal starts gets `503 shutting-down` and is never sent
- a request in flight when the signal lands gets a real outcome (`200`, `429` or `504`), never a reset connection
- the configured drain budget is ≥ `PublishDeadline` + margin; a configuration that breaks this refuses to start and names both values
- `close` finishes inside the pod's grace period, whatever the broker is doing — or the limit is written down as measured

**Modules:** `server`. **Screens:** none. **Endpoints:** endpoint-records, endpoint-probes.

**Scenarios (target):**

- *drain covers the deadline* — Given 64 concurrent publishers and a broker that is up, when
  `SIGTERM` lands at a random moment, then every client got a status. Every `200` is in the topic
  and no `503` is.
- *broker gone at shutdown* — Given the broker is stopped, when `SIGTERM` lands, then in-flight
  requests end as `504`, and the process exits within the grace period.
- *misconfigured budget* — Given drain 3 s and deadline 5 s, when the service starts, then it
  exits `1` and the message names `PUBLISH_DEADLINE_MS` and the drain deadline.

## 5. Screens

None. The service has no user interface, so there are no states, artboards or fixtures, and no
design brief follows from this one.

### 5a. Sample data

| Entity | Values |
|---|---|
| `Topic` | allowlisted `orders` (3 partitions), `payments` (1 partition); **not** allowlisted `audit` |
| `PublishRequest` | key `order-1042`; value `{"orderId":1042,"amount":"19.90","currency":"EUR"}`; header `trace-id: 7f3a9c` |
| `PublishDeadline` | `5000` ms; drain budget in the misconfiguration scenario `3000` ms |
| Load | 64 concurrent publishers — the concurrency kafkakn §2.14's second sweep used |
| Today | 2026-10-01, fixed |

## 6. API

### endpoint-records: publishing

Contract class: none shared — no Kotlin client exists, so the wire types live in `server` (§7).
Service: `server`.

| Method | Path | Tier | Min role | Request | Response | Errors | In the public schema? |
|---|---|---|---|---|---|---|---|
| `POST` | `/topics/{topic}/records` | behind the proxy (trusted network) | — | body = value bytes; `Record-Key` header (optional); `Record-Header-<name>` headers → record headers in order | `200` `{topic, partition, offset, timestamp}` | `404 topic-not-found`; `413 record-too-large`; `429 not-queued` + `Retry-After`; `502 broker-rejected` (a definite refusal, §8 K4); `503 shutting-down`; `504 outcome-unknown` | yes |

### endpoint-probes: kore's surface, as keel mounts it

`/health/*` probes and `/version`, unchanged from keel (`installKoreProbes`, `installKoreVersion`)
— linked, not redesigned. Whether readiness follows broker reachability is an open question
(§10). By default it does not.

Wire conventions: error bodies are `{"error": "<kebab-code>", "detail": "<sentence>"}`, and the
code is part of the contract, the sentence is not (kafkakn's rule for its own errors). Every `504`
body carries `"outcome": "unknown"` and `"retrySafe": false`. The status set is **closed**: a
thrown `send` that the service cannot classify is `504`, never `500`, because an unclassified
failure is exactly the case where the service does not know.

## 7. Modules and services

| Module | Role | Stack | Storage / config | Depends on | Publishes | New or existing |
|---|---|---|---|---|---|---|
| `server` | routes, the outcome mapping, kore wiring, both entry points | Ktor CIO, kore, kafkakn-core | none; env: `KAFKA_BOOTSTRAP_SERVERS`, `TOPICS`, `PUBLISH_DEADLINE_MS`, `MAX_RECORD_BYTES`, pass-through `KAFKA_*` producer keys | kafkakn-core, kore | container image | from keel, reshaped: the `item` feature and SQLite are removed |
| `distribution` | the JVM distribution (zavarnik AOT cache) | keel's convention | — | `server` | into the same image | from keel |
| `ci/` | broker fixture, pause/stop scripts, the SIGTERM oracle | shell, Docker | — | — | — | new |
| **kafkakn** `kafkakn-core` | the producer; stage 3 changes land **there** | — | — | — | reposilite snapshot | existing — github.com/youndie/kafkakn |

Deploy: not decided.

## 8. Decisions and hypotheses

| # | Decision / claim | Why | Verified against / *hypothesis* |
|---|---|---|---|
| K1 | `send` is cancellable on both arms, so `withTimeout` already bounds the caller's wait | the premise "the API has no way to bound the wait" | code: `KafkaProducer.jvm.kt:241` (`suspendCancellableCoroutine` on `Dispatchers.IO`), `KafkaProducer.native.kt:421` (`slot.await()`), 2026-09-26. **Not** in `producer-contract.md` and not tested: `grep cancel` there finds only `partitionsFor` and transactions |
| K2 | cancelling after enqueue does not recall the record | the reason `504` must say "unknown" | KDoc at both lines above. That the record **does** land afterwards is a *hypothesis* until the "queued, broker silent" scenario shows it |
| K3 | the caller cannot tell a cancel during enqueue (clean: `KafkaProducer.native.kt:447` says so) from one after. Both surface as `CancellationException` | the `429` / `504` split depends on it; this is the change the bridge forces on kafkakn | code, 2026-09-26. The shape of the fix is kafkakn's decision, not this brief's |
| K4 | a thrown `send` is not "not written": a local message timeout on an in-flight record may be persisted | decides `502` vs `504` | *hypothesis*. librdkafka's `rd_kafka_message_status` (NOT/POSSIBLY/PERSISTED) is expected in the bundled 2.13.0 header, which was not read here. The Java client exposes no such status |
| K5 | a portable library-side bound does not exist: native `message.timeout.ms` is a platform key (contract, "300 000 ms by default"), `delivery.timeout.ms` is not in the contract; the queue-full wait is `BACKPRESSURE_LIMIT_MS = 120_000` on native and `max.block.ms` on the JVM | why the deadline lives in the bridge, not in producer config | `producer-contract.md`, `KafkaProducer.native.kt:802`, 2026-09-26 |
| K6 | `close` flushes and has no bound. With the broker gone it may wait out `message.timeout.ms` — beyond a 30 s grace | the "broker gone at shutdown" scenario | *hypothesis*; contract says "flushes, then releases" and nothing about time |
| K7 | drain-then-close order holds under SIGTERM with kafkakn behind Ktor + kore | shutdown is re-proved, not discovered | kafkakn research §2.14 (xyk, 91 149 accepted, 0 lost; 64-sender sweep 130 681, 0 lost) |
| B1 | keel is the base: kore's refusal → engine drain → `ShutdownParticipant` order already wired | "shortest path from keel" | `keel/server/.../Wiring.kt`, 2026-09-26 |
| B2 | "behind a reverse proxy" means plain HTTP only; keel installs no `ForwardedHeaders` | nothing in v1 needs the client address | grep of keel, 2026-09-26 |
| B3 | toolchain must move to kafkakn's: keel pins Kotlin 2.4.10 / sborka 0.4.0.82, and kafkakn builds on sborka 0.4.0.86 with Kotlin from the portfolio catalogue | a klib from a newer compiler may not be readable by an older one | versions read from both catalogues 2026-09-26. The incompatibility itself is a *hypothesis*; item 1 settles it |
| B4 | kafkakn comes from reposilite as `0.1.0-SNAPSHOT`, and the published native klib must link from outside | kafkakn B-13 found 14 unresolved symbols once before | kafkakn B-13 / B-15; re-checked by item 1's acceptance |
| D1 | status set closed: `200/404/413/429/502/503/504`; unclassified → `504` | an honest bridge never answers `500` for "I don't know" | design decision, 2026-09-26 |
| D2 | value = raw body, key/headers from HTTP headers | no parse of the payload; the body stays bytes | design decision; alternative (JSON envelope) out of scope |
| D3 | both builds ship and run the same scenarios | the JVM build on the official client is the oracle for the native one, as in kafkakn | user, 2026-09-26 |
| D4 | docs in English | keel, kafkakn and xyk all are; the portfolio default is Russian, so this is a choice | *confirm* (§10) |

## 9. Backlog seeds

| Stage id | Stage | What is true when it closes |
|---|---|---|
| `stage-1-skeleton` | from keel to a bridge that builds | both builds link against the published kafkakn and start |
| `stage-2-publish` | the happy path | `200` means "in the topic at that offset", on both builds |
| `stage-3-bounded-wait` | the deadline and what it forces on kafkakn | `429` and `504` are both true statements, proven by reading the topic |
| `stage-4-shutdown` | SIGTERM under load | every answered request's status agrees with the topic; exit inside the grace |

| # | Title | Priority | Size | Stage | Feature | Blocked by | Acceptance |
|---|---|---|---|---|---|---|---|
| 1 | repository from the keel template: drop `item` and SQLite, toolchain onto kafkakn's sborka/Kotlin, depend on `kafkakn-core` | P0 | M | `stage-1-skeleton` | feature-publish-over-http | — | `./gradlew build` green; the native binary links from reposilite coordinates alone (no kafkakn checkout) |
| 2 | broker fixture and topic reader in `ci/` | P0 | S | `stage-1-skeleton` | feature-publish-over-http | — | a script creates §5a's topics and prints a record by `(topic, partition, offset)` with the distribution's own consumer |
| 3 | `POST /topics/{topic}/records` → `200` with metadata; allowlist `404`, `413` | P1 | M | `stage-2-publish` | feature-publish-over-http | 1, 2 | the *acknowledged*, *not allowlisted*, *oversized* scenarios pass on both builds, the oracle reading the topic |
| 4 | **kafkakn:** write cancellation of `send` into the contract and test it on both arms | P0 | M | `stage-3-bounded-wait` | feature-publish-over-http | — | a contract section on the two cancellation moments; a `commonTest` in which a `send` cancelled after enqueue is found in the topic afterwards (K2) |
| 5 | **kafkakn:** let the caller tell "never queued" from "queued, fate unknown" | P0 | M | `stage-3-bounded-wait` | feature-publish-over-http | 4 | both arms report the two cases differently to a caller whose wait was cut; shape decided in kafkakn |
| 6 | the deadline: `429 not-queued` / `504 outcome-unknown` | P0 | M | `stage-3-bounded-wait` | feature-publish-over-http | 3, 5 | *queue full* and *queued, broker silent* pass on both builds; no request outlives deadline + margin |
| 7 | **kafkakn:** a thrown `send` says whether the record may be persisted (K4) — *question* item until the header and the Java client are read | P2 | S | `stage-3-bounded-wait` | feature-publish-over-http | 4 | either `502` is reachable with a measured definite refusal, or the item records that every throw is `504` |
| 8 | refuse to start when the drain budget < deadline + margin | P1 | S | `stage-4-shutdown` | feature-shutdown-without-loss | 6 | *misconfigured budget* scenario passes |
| 9 | measure `close` with the broker gone; upstream a bound to kafkakn if it exceeds the grace (K6) | P1 | S | `stage-4-shutdown` | feature-shutdown-without-loss | 6 | the time is measured on both builds and written down; if > grace, a kafkakn item exists |
| 10 | the SIGTERM oracle: 20 rounds × 64 publishers, client-side ledger vs topic, broker-stopped positive control | P1 | M | `stage-4-shutdown` | feature-shutdown-without-loss | 8, 9 | every `200` in the topic, no `429/503` in it, `504`s counted both ways; the control moves the counts |

## 10. Open questions

- [ ] the repository's name — `mostik` is a working name only (owner)
- [ ] docs in English (D4) — like the three neighbours, against the portfolio default (owner)
- [ ] should readiness follow broker reachability? Without it, a dead broker turns every request into `504` rather than being pulled from the proxy (owner, before item 6)
- [ ] `Retry-After` value for `429`: fixed, or derived from the queue's drain rate (implementer, item 6)
- [ ] if kafkakn's answer to item 5 is "not possible on one arm", does the bridge fall back to `504` for every expiry on both builds, or diverge per build? (owner, after item 5)
