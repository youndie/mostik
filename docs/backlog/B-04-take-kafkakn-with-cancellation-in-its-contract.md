---
id: B-04
title: "Take a kafkakn snapshot whose contract says what a cancelled send leaves behind"
status: wip
priority: P0
size: S
stage: stage-3-bounded-wait
blocked_by: [B-01]
---

# B-04 — take a kafkakn snapshot whose contract says what a cancelled `send` leaves behind

**Blocked outside this repository**, and that is why the item exists. `blocked_by` names only this
repository's items, and without this item the dependency would be invisible to the index. Feature:
publish over HTTP.

mostik's deadline answers depend on two kafkakn items, filed in youndie/kafkakn#94:

- kafkakn B-73 writes into the contract what a cancelled `send` means for its record, and measures it
  on both arms (research §1.2, H1);
- kafkakn B-74 lets a caller whose wait was cut tell "never queued" from "queued, outcome unknown"
  (research §1.3).

**Both merged on 2026-09-27** (kafkakn #95 and #96, `fe4f1c4`). B-73 measured that a record cut after
queueing lands, and that on the JVM a cut while waiting for room is not honoured. B-74 added
`enqueue(record): Delivery` and `RecordNotQueuedException`, bounded by `max.block.ms` on both arms
(research §1.3, settled).

The published snapshot is still from 2026-09-25 and holds neither. A merged pull request is not a
publication: kafkakn republishes its snapshot by hand, by running its `publish` workflow (research §1.8).

- **The decision and its reason.** This item is done when the snapshot mostik resolves carries B-74's
  API. mostik does not build kafkakn from a checkout, because the published klib is what a stranger
  links (research §1.8, consequence 1).

- AC: `maven-metadata.xml` for `kafkakn-core` shows a `lastUpdated` after 2026-09-27, when kafkakn B-74
  merged. mostik compiles a call to `enqueue`, `Delivery.await()` and `RecordNotQueuedException` on both
  builds.
- Anchors: `gradle/libs.versions.toml`.

## Progress (2026-09-27)

- **The first half of the AC is met.** The snapshot was republished by kafkakn's `publish` run of
  2026-09-26 22:40 UTC, built from `fe4f1c4`, the B-74 merge. `maven-metadata.xml` now says
  `lastUpdated 20260926224549`, and the build is `0.1.0-20260926.224535-6`, the same for `kafkakn-core-jvm`
  and `kafkakn-core-linuxx64`. The published JVM jar holds `io/github/youndie/kafkakn/Delivery.class` and
  `RecordNotQueuedException.class`. This was read from the jar itself, not inferred from the date.
- **The second half waits on B-01**, which is this item's blocker: mostik does not depend on kafkakn until
  then, so there is nothing yet to compile a call to `enqueue` against.
