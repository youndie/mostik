---
id: B-06
title: "Does a thrown send mean the record was not written?"
status: question
priority: P2
size: S
stage: stage-3-bounded-wait
blocked_by: [B-04]
---

# B-06 — does a thrown `send` mean the record was not written?

`502 broker-rejected` in research D2 promises "not written". Research H3 doubts that a thrown `send`
always means it: a local message timeout on a record that was in flight may have been persisted.
Feature: publish over HTTP.

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
