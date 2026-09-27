---
id: B-06
title: "Does a thrown send mean the record was not written?"
status: done
priority: P2
size: S
stage: stage-3-bounded-wait
epic: feature-publish-over-http
blocked_by: [B-04]
---

# B-06 — does a thrown `send` mean the record was not written?

`502 broker-rejected` in research D2 promises "not written". Research H3 doubts that a thrown `send`
always means it: a local message timeout on a record that was in flight may have been persisted.
Feature: [feature-publish-over-http](../features/feature-publish-over-http.md).

- **What has to be read first.** librdkafka's `rd_kafka_message_status` (NOT / POSSIBLY / PERSISTED),
  expected in the bundled 2.13.0 header and not read yet. Then which exceptions of the Java client
  mean a refusal the broker named (for example `RecordTooLargeException`).
- The possible answers, for the owner:
  1. `502` only for refusals the broker named, on both arms. Everything else is `504`.
  2. File a kafkakn item that exposes the persisted status on the arm that has it.
  3. Drop `502`: every throw is `504`.
- Not covered: acting on the answer. That is a new item once the owner chooses.

- AC: the item records what the header and the Java client were read to say, with addresses, and the
  owner's choice.
- Anchors: `youndie/kafkakn@2f209b0!/kafkakn-core/src/nativeMain/kotlin/io/github/youndie/kafkakn/KafkaProducer.native.kt`.

## Decision (2026-09-27): choice 3, `502 broker-rejected` is dropped

The owner asked for the reading, and then for the decision to be taken on it.

**What was read:**

- **librdkafka 2.13.0**, the version kafkakn bundles
  (`confluentinc/librdkafka@v2.13.0!/src/rdkafka.h`, lines 1625–1655):
  - `rd_kafka_msg_status_t` has three values:
    - `NOT_PERSISTED`: never transmitted, or failed with an error saying it was not written; "retry risks
      ordering, but not duplication";
    - `POSSIBLY_PERSISTED`: transmitted, no acknowledgement; "retry risks ordering and duplication";
    - `PERSISTED`.
  - `rd_kafka_message_status(rkmessage)` reads it in the delivery report.
- **kafkakn's native arm** (`youndie/kafkakn@84008f4!/kafkakn-core/src/nativeMain/kotlin/io/github/youndie/kafkakn/KafkaProducer.native.kt`):
  it does **not** call `rd_kafka_message_status`. Every failed delivery completes the waiting slot with
  `KafkaProduceException("<topic>: <rd_kafka_err2str>")`, so the status is lost before it reaches a caller.
- **kafkakn's JVM arm** (same commit, `KafkaProducer.jvm.kt`, `send`/`enqueue`): the Java client's exception goes
  through as it is, `failure.asFenced() ?: failure`. That is a JVM type, which mostik's common code cannot name.
- **kafka-clients 4.3.1** (`org.apache.kafka:kafka-clients:4.3.1!/org/apache/kafka/clients/producer/ProducerConfig.java`,
  `DELIVERY_TIMEOUT_MS_DOC`): `delivery.timeout.ms` bounds "the time to await acknowledgement from the broker", and
  failure may be reported earlier on "an unrecoverable error". The callback carries an exception and no
  persistence status. A record that expired in flight is not known to be unwritten.
- **kafkakn's contract**, error table: a delivery failure is "`send` throws, and the message carries the broker's
  own text". No type is named, and error text is explicitly not contract.

**The decision: choice 3.** Every failure after queueing is `504 outcome-unknown`, and `502 broker-rejected` is
removed from the endpoint document. `502` stays with its B-05 meaning, `producer-refused`: refused *before*
queueing, so provably not written.

- Choice 1 (`502` for refusals the broker named) cannot be written portably. The only thing that names the
  refusal is text, and text is not contract. Matching on text would be exactly the unchecked classification the
  contract refuses.
- Choice 2 (kafkakn exposes the status) would give native a real answer and the JVM none. That is two builds
  answering one situation differently, the thing B-76 was filed to remove. Nobody needs the distinction yet.
  If a consumer ever needs "possibly written" versus "not written" after queueing, that is the kafkakn item to
  file then.
- `504` is never false here, only pessimistic. A client that retries a `504` has been told a retry may write twice.
