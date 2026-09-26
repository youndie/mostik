---
id: B-02
title: "A broker fixture, and a reader the scenarios use as their oracle"
status: done
priority: P0
size: S
stage: stage-1-skeleton
epic: feature-publish-over-http
---

# B-02 — a broker fixture, and a reader the scenarios use as their oracle

Every scenario in this backlog is decided by reading the topic, not by asking mostik or the producer.
A producer asked whether it delivered answers yes. That is kafkakn's rule
(`youndie/kafkakn@2f209b0!/docs/research/research-architecture.md` §2.14), and it is the only way to tell
whether a `504` record was written. Feature: [feature-publish-over-http](../features/feature-publish-over-http.md).

- **The decision and its reason.** A script starts a single broker in Docker and creates the sample
  topics: `orders` with 3 partitions and `payments` with 1. A second script prints the record at
  `(topic, partition, offset)`, and all records carrying a given key, using the broker distribution's
  own console consumer. It never uses kafkakn, which is the thing under test.
- A third script pauses and resumes the broker (`docker pause`). "Silent" is the state B-05 needs; a
  stopped broker refuses connections and is a different scenario.
- Not covered: a multi-broker cluster.

- AC: from an empty machine with Docker, one command brings up the broker with both topics. A record
  written with the distribution's console producer is printed back by `(topic, partition, offset)` and
  by key.
- Anchors: `ci/broker/broker.sh`, `ci/broker/docker-compose.yml`.

## Findings (2026-09-27)

Everything ran on the Linux box, which has Docker; the Mac has none. There is no CI.

- **AC: one command brings up the broker with both topics, and a record written by the distribution's
  console producer is printed back by place and by key.** `ci/broker/broker.sh selftest` exits 0:

  ```
  broker answers on 127.0.0.1:19092; topics: orders:3 payments:1
  selftest: written and read back at partition 1 offset 0, by key and by place:
    Partition:1 | Offset:0 | NO_HEADERS | selftest-1790464135540669641 | {"orderId":1042,"amount":"19.90","currency":"EUR"}
  ```

  The selftest carries its own positive control: a key nobody wrote is found nowhere. And the read by key
  must return exactly one line, so a producer that silently wrote nothing fails it.
- **The first selftest run failed, and correctly.** Kafka 4.3 prints its deprecation notice for
  `--property` on **stdout**, so the notice became the first line of the record read by place, and the
  comparison with the read by key failed. The script now uses `--formatter-property` and
  `--reader-property`, the options the notice names.
- **`pause` is silence, not refusal.** With the broker paused, a TCP connection to 19092 is still accepted
  (Docker's proxy holds it) and nothing answers. After `resume`, `kafka-broker-api-versions.sh` answers
  again. That is B-05's "broker silent".
- **For B-05: nothing can be read while the broker is paused.** `docker exec` into a paused container is
  refused ("unpause the container before exec"), so the reader runs only after `resume`. The scenario's
  order is therefore: pause, publish, deadline, resume, read.
- **Its own name and port:** `mostik-broker` on 19092. kafkakn's fixture is `kafkakn-broker` on 9092 on the
  same shared machine, and neither stops the other's runs.
- The broker is `apache/kafka:4.3.1`, the version kafkakn's suite runs against.
