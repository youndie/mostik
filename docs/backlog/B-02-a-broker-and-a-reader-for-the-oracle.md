---
id: B-02
title: "A broker fixture, and a reader the scenarios use as their oracle"
status: wip
priority: P0
size: S
stage: stage-1-skeleton
---

# B-02 — a broker fixture, and a reader the scenarios use as their oracle

Every scenario in this backlog is decided by reading the topic, not by asking mostik or the producer.
A producer asked whether it delivered answers yes. That is kafkakn's rule
(`youndie/kafkakn@2f209b0!/docs/research/research-architecture.md` §2.14), and it is the only way to tell
whether a `504` record was written. Feature: publish over HTTP.

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
- Anchors: `ci/broker/` (new).
