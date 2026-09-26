---
id: research-architecture
title: mostik — architecture research
type: research
status: active
date: 2026-09-26
---

# Research: the architecture of mostik

mostik is an HTTP → Kafka bridge. A service that speaks HTTP and not Kafka posts one record and gets an
answer that says what happened to the record: *written, here is its offset*, *not written, try again*,
or *nobody knows yet*. The usual bridge answers `200` as soon as the record is queued in the local
client. Such a bridge either waits for the broker without a bound, or cuts the wait and answers `503`
for a record that lands anyway. mostik answers only after the broker's acknowledgement, under a
deadline, and keeps the answer true when the deadline wins.

It is built on two of this portfolio's public repositories, and it exists to test one of them:

- [keel](https://github.com/youndie/keel) is the base. It supplies a Ktor server that ships twice: a
  Kotlin/Native binary and a JVM distribution in one image, with kore's ordered shutdown already wired.
- [kafkakn](https://github.com/youndie/kafkakn) is the producer. Its native arm is librdkafka and its
  JVM arm is the official Java client. mostik is the first consumer of kafkakn whose HTTP response
  waits for the acknowledgement **under a deadline**. That makes it the first to ask what a cancelled
  `send` means.

This document records **verified facts** (what was read in code and artefacts, with the address),
**decisions**, and **risks**. Anything unverified is a hypothesis and names the item that settles it.

**The state of the tree.** The code on `main` is keel's template at
[`youndie/keel@6be238d`](https://github.com/youndie/keel/tree/6be238d). keel's own documents describe
it at `youndie/keel@6be238d!/docs/services/keel-server.md` and are not copied here. The feature, API and
service documents for mostik are drafted on the branch **docs/drafts**. They reach `main` when the code
they describe does (§4).

---

## 1. Verified facts

### 1.1 `send` is cancellable on both arms, so a deadline is implementable today

The premise mostik started from was: "if kafkakn's API has no way to bound the wait for `send`, this
service will expose it first". The premise is wrong. The wait can already be bounded.

| Fact | Where verified |
|---|---|
| The JVM arm suspends in `suspendCancellableCoroutine` inside `withContext(Dispatchers.IO)` | `youndie/kafkakn@2f209b0!/kafkakn-core/src/jvmMain/kotlin/io/github/youndie/kafkakn/KafkaProducer.jvm.kt` (`send`) |
| The native arm suspends in `enqueue`'s backpressure loop (`delay`) and then in `slot.await()` on a `CompletableDeferred` | `youndie/kafkakn@2f209b0!/kafkakn-core/src/nativeMain/kotlin/io/github/youndie/kafkakn/KafkaProducer.native.kt` (`send`, `enqueue`) |
| A cancellation during `enqueue` unparks the slot and rethrows | same file: `catch (failure: Throwable) { unpark(id); throw failure }` |

**Consequence.** `withTimeout(deadline) { producer.send(record) }` works on both builds without a
change to kafkakn. The question mostik raises is not *whether* the wait can be cut. It is **what the
answer means once it has been**.

### 1.2 Cancelling does not recall a queued record, and only the KDoc says so

| Fact | Where verified |
|---|---|
| Both arms' KDoc: cancelling "stops the caller waiting; it does not recall a record the client has already accepted" | the two files of §1.1, at `send` |
| The contract document says nothing about cancelling `send`. The word appears only for `partitionsFor` and the transactional calls | `youndie/kafkakn@2f209b0!/docs/api/producer-contract.md` |
| No test cancels a `send`. `ProduceTest` catches `CancellationException` only to tell a cancelled test from a failing producer | `youndie/kafkakn@2f209b0!/kafkakn-core/src/commonTest/kotlin/io/github/youndie/kafkakn/ProduceTest.kt` |

**Consequence.** After the deadline, the record may still reach the broker. An answer of `503`, "not
written, try again", would then be false in the direction nobody guards against: the retry writes
the record twice. The answer for an expired deadline is therefore `504` with the body saying the
outcome is unknown and a retry is unsafe (D2).

That the record **does** land after a cancel is not yet measured anywhere. It is filed upstream as
[kafkakn B-73](https://github.com/youndie/kafkakn/pull/94) (H1).

### 1.3 A caller cannot tell "never queued" from "queued, outcome unknown"

| Fact | Where verified |
|---|---|
| On native, a cancel during the backpressure loop is the one clean moment: the KDoc says the record was "never queued" | `KafkaProducer.native.kt` at `enqueue`, same address as §1.1 |
| Both moments reach the caller as the same `CancellationException` | the two `send` implementations; neither translates it |
| On the JVM the waiting for metadata and buffer room happens **inside** the Java client's `send`, which blocks up to `max.block.ms`. A coroutine cancelled there cannot interrupt it | `KafkaProducer.jvm.kt`, the comment above `withContext(Dispatchers.IO)`; the blocking is kafkakn's measurement (`JvmDispatcherSeamTest`, 6 019 ms) |

**Consequence 1.** The `429` answer, "provably never queued, retry is safe", has no way to be
computed today. Until kafkakn gives the caller that distinction, every expired deadline is `504` on
both builds. The `429` path exists in the contract and is unreachable, and the documents say so. This is
filed upstream as [kafkakn B-74](https://github.com/youndie/kafkakn/pull/94).

**Consequence 2 (read in the code, not measured).** On the JVM there may be no clean moment at all,
other than before the dispatch to `Dispatchers.IO`. If that holds, the `429` path stays unreachable on
the JVM build even after B-74, and the two builds answer differently. That is open question 4.

### 1.4 No library-side bound is portable, so the deadline lives in mostik

| Fact | Where verified |
|---|---|
| On native, an unanswered record waits out `message.timeout.ms`, "300 000 ms by default". That key is a librdkafka key and not part of the contract's portable set | `youndie/kafkakn@2f209b0!/docs/api/producer-contract.md`, the part on how long an unverifiable peer takes to fail |
| `delivery.timeout.ms`, the Java client's equivalent, does not appear in the contract | same document, searched |
| The native wait for queue room ends at a fixed `BACKPRESSURE_LIMIT_MS = 120_000` and throws `KafkaProduceException`. The JVM's ends at `max.block.ms` and throws the Java client's `TimeoutException` | `KafkaProducer.native.kt` (companion constants); `KafkaProducer.jvm.kt` |

**Consequence.** A deadline configured in producer keys would be two different keys with two different
exception types, and neither would bound the whole request. mostik's `PUBLISH_DEADLINE_MS` is enforced by
mostik around `send`. The producer keys pass through for operators and bound nothing mostik promises.

### 1.5 The shutdown order is already measured — mostik re-proves it rather than discovering it

| Fact | Where verified |
|---|---|
| Twenty `SIGTERM`s at random moments inside a Ktor + kore service on kafkakn: 91 149 accepted, 0 missing from the topic; with 64 concurrent senders, 130 681 and 0; the broker-stopped control lost exactly one record per sender | `youndie/kafkakn@2f209b0!/docs/research/research-architecture.md` §2.14 (the service was [xyk](https://github.com/youndie/xyk)) |
| That run's publisher awaited the acknowledgement inside the request, which is mostik's shape | same section: "a record is either inside somebody's `send` or finished" |
| kafkakn's `close` "flushes, then releases", and states no bound on the time | `producer-contract.md`, `### close` |

**Consequence.** Drain-then-close holds, and mostik adds one new question: how the drain budget
relates to the publish deadline (§1.6). What `close` does when the broker is gone is not measured (H2).

### 1.6 keel's shutdown sequence, and kore's defaults under it

| Fact | Where verified |
|---|---|
| The sequence is `announce(AnnounceNotReady)` → `drain(EngineDrain(server, drain, drain + 5 s))` → `pool(ShutdownParticipant)`. The participant (today the SQLite pool) is closed after the drain and never in `ApplicationStopping` | `server/src/commonMain/kotlin/io/github/youndie/keel/Wiring.kt` |
| New requests during shutdown are refused by `installShutdownRefusal(isShuttingDown = …)` | same file, `keelModule` |
| `ShutdownDeadlines()` defaults: `preDrainWait` 5 s, `drain` 15 s, `releaseGroup` 3 s; grace period 30 s (the Kubernetes default) | `youndie/kore@47825a6!/kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/ShutdownPlan.kt`; `git log -S` shows the defaults unchanged since they were introduced, so they are the values of the pinned 0.1.4 |
| The Ktor engine's `shutdownGracePeriod` is set from `deadlines.drain` | `Wiring.kt` |

**Consequence 1.** The producer is a `ShutdownParticipant` in the slot the SQLite pool holds now, and
nothing else in the sequence changes.

**Consequence 2.** A request in flight when the drain starts needs up to `PUBLISH_DEADLINE_MS` to get a
real answer. If the drain is shorter than that, the engine cuts the request, and the client sees a reset
connection, which is worse than a `504`. So `drain ≥ PUBLISH_DEADLINE_MS + margin` is a start-up check
(D6). With the defaults (a drain of 15 s and a deadline of 5 s) the check holds.

### 1.7 The toolchains are already aligned — *deviation from the brief*

The brief assumed that keel pins Kotlin 2.4.10 and sborka 0.4.0.82, and that the first item would move
it onto kafkakn's toolchain, because an older compiler may refuse a newer klib. That was read from a
local keel checkout that was **two commits behind**.

| Fact | Where verified |
|---|---|
| keel takes sborka `0.4.0.89` and the compiler from the shared `wip` catalog (keel #37), with Ktor `3.6.0` (#38) | `gradle/libs.versions.toml`, `settings.gradle.kts` of this tree (= `youndie/keel@6be238d`) |
| kafkakn takes sborka `0.4.0.86` and the compiler from the same catalog | `youndie/kafkakn@2f209b0!/gradle/libs.versions.toml` |
| kafkakn's README: "a klib carries metadata that a build on another compiler refuses", known to work with `2.4.20` | `youndie/kafkakn@2f209b0!/README.md` |

**Consequence.** Both repositories take the compiler from one place, so B-01 does not move a toolchain.
It only has to show that the published klib resolves and links (§1.8).

### 1.8 kafkakn is a reposilite snapshot, and a stranger's link once failed

| Fact | Where verified |
|---|---|
| `io.github.youndie.kafkakn:kafkakn-core`, only version `0.1.0-SNAPSHOT`, last updated 2026-09-25 06:33 | `https://reposilite.kotlin.website/snapshots/io/github/youndie/kafkakn/kafkakn-core/maven-metadata.xml`, read 2026-09-26 |
| The native klib carries librdkafka and its TLS stack inside, so "a downstream link needs no configuration of its own" | `youndie/kafkakn@2f209b0!/README.md` |
| That sentence was false once: the suite linked and a stranger's build failed with 14 undefined symbols | `youndie/kafkakn@2f209b0!/docs/backlog/B-15-native-klib-carries-no-c.md` |
| This tree's `pluginManagement` already names the same repository, filtered to `io.github.youndie.*` | `settings.gradle.kts` |

**Consequence 1.** B-01's acceptance is a native **link** from the published coordinates, not a
compile.

**Consequence 2.** The snapshot predates stage 15 of kafkakn. The changes kafkakn B-73 and B-74 make
reach mostik only after the snapshot is republished, which kafkakn does by hand. So B-04 asks for a
republished snapshot, not for a merged pull request.

### 1.9 "Behind a reverse proxy" means plain HTTP, and nothing more

| Fact | Where verified |
|---|---|
| No `ForwardedHeaders` or `XForwardedHeaders` plugin is installed | `grep` of `server/src` in this tree, 2026-09-26 |

**Consequence.** Nothing in the first version reads the client's address, so nothing is added. The proxy
also does authentication (D4). mostik trusts its network and limits what it can write to with a topic
allowlist.

---

## 2. Decisions

### D1. One record per request, the body is the value

The request body is the record's value, byte for byte, and is never parsed. The key comes from the
`Record-Key` header. Record headers come from `Record-Header-<name>` headers, in order. The rejected
alternative is a JSON envelope with a base64 value, the shape of other REST proxies. It parses what
mostik has no reason to read, and it makes every caller encode. Batches are out of the first version.

### D2. A closed status set, and `504` for "unknown" (owner, 2026-09-26)

`200` acknowledged · `404` topic not in the allowlist · `413` body over `MAX_RECORD_BYTES` · `429` provably
never queued · `502` a definite refusal by the broker · `503` shutting down · `504` outcome unknown.

A `send` that throws something mostik cannot classify is `504`, not `500`, because an unclassified
failure is exactly the case in which mostik does not know. The owner chose `504` with an explicit
`retrySafe: false` over two alternatives:

- `503` for every expiry, which is sometimes "written";
- an idempotency key, which moves deduplication onto the reader and doubles the first version.

The price is §1.3: until kafkakn B-74, `429` is unreachable. Whether `502` is reachable is H3.

### D3. Both builds ship, and both run every scenario (owner, 2026-09-26)

keel ships a native binary and a JVM distribution in one image, and mostik keeps both. The JVM build runs
on the official Java client, so it is the oracle for the native one one level above kafkakn's own
differential suite: the same scenario, the same broker, two builds, one expected status.

### D4. No authentication in the service; a topic allowlist instead (owner, 2026-09-26)

The reverse proxy authenticates. `TOPICS` in the configuration is the allowlist. A topic outside it
answers `404` before the producer is touched, so mostik is never an open producer into any topic the
broker has.

### D5. No `shared` module

No Kotlin client exists and none is planned, so the wire types live in `server`. A module holding a
contract with no second reader is a copy with a build of its own.

### D6. The drain budget is checked against the deadline at start-up

mostik refuses to start when `drain < PUBLISH_DEADLINE_MS + margin`, and the message names both values.
kore's configuration refuses a missing value the same way, so this is one more refusal of the same kind.
The reason is §1.6, consequence 2.

### D7. The kafkakn changes land in kafkakn

mostik does not wrap `send` in a private notion of "queued". The distinction belongs to the library,
where both arms can be held to it by the differential suite. Two items are filed upstream (kafkakn
B-73, B-74, youndie/kafkakn#94). mostik's B-04 takes their result. The rejected alternative is a local
counter of "calls that entered `send`", which cannot tell the two moments apart either.

### D8. The backlog is one file per item

About ten items would fit the checklist form, but the `backlog-item` loop reads only the file-per-item
form, and this tree already carries its generator (`scripts/backlog_index.py`).

### D9. Documents in English

The same as keel, kafkakn and xyk. The portfolio's default is Russian, so this is a choice, and it is
open question 5 until the owner confirms it.

---

## 3. Hypotheses, risks and open questions

**H1. A record whose `send` was cancelled after it was queued is written afterwards.** Settled by
kafkakn B-73. If it is refuted (the record is *not* written), `504` is still correct but pessimistic.

**H2. `close` with the broker gone waits out `message.timeout.ms`, beyond a 30 s grace period.** Settled
by B-08. If it holds, the process is `SIGKILL`ed with records still in the producer. Every one of them
belongs to a request that was already answered `504`, so no answer becomes false. What changes is only
how the process ends. A bound on `close` would then go to kafkakn.

**H3. A thrown `send` does not always mean "not written".** A local message timeout on a record that was
in flight may have been persisted. librdkafka's `rd_kafka_message_status` (NOT / POSSIBLY / PERSISTED)
is expected in the bundled 2.13.0 header, which has not been read. The Java client has nothing
equivalent. Settled by B-06. Until then `502` is only for refusals the broker names (for example
`RECORD_TOO_LARGE`), and everything else is `504`.

**Risk 1. The proxy times out before mostik does, and answers `504` itself.** The client then sees a
`504` without mostik's body, and cannot tell it from mostik's. Mitigation: every mostik error body
carries its `error` code, so a `504` without one is known to be the proxy's. The deployment notes
state that the proxy's upstream timeout must exceed `PUBLISH_DEADLINE_MS` + margin. mostik cannot read
the proxy's configuration, so this is documented rather than checked.

**Risk 2. With the broker gone, every request becomes `504` and readiness stays green.** The proxy keeps
routing to a service that can only answer "unknown". Mitigation: open question 3. The default is to
leave readiness alone, because a bridge pulled out of rotation on every broker blip turns honest `504`s
into the proxy's `502`s.

**Open question 1 (owner, before B-05).** Should readiness follow broker reachability?

**Open question 2 (implementer, B-05).** Is `Retry-After` on `429` a fixed value, or derived from how fast
the queue drains?

**Open question 3 (owner, after kafkakn B-74).** If B-74 can tell "never queued" apart on only one arm, do
both builds answer `504` for every expiry, or does each build answer what it can?

**Open question 4 (kafkakn B-73).** Does the JVM arm have a clean cancellation moment at all (§1.3,
consequence 2)?

**Open question 5 (owner).** Documents in English (D9)?

---

## 4. What happens next

The order is in [backlog.md](../../backlog.md). First the skeleton (B-01, B-02). Then the happy path
(B-03), which is when the drafted documents on **docs/drafts** get their first code anchors. Then the
deadline, which depends on kafkakn: B-04 waits for a republished snapshot carrying B-73 and B-74. The
shutdown items come last: B-07 checks the drain budget, B-08 measures `close` with the broker gone, and
B-09 is the `SIGTERM` oracle.
