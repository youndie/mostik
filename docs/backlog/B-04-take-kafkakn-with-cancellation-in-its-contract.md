---
id: B-04
title: "Take a kafkakn snapshot whose contract says what a cancelled send leaves behind"
status: open
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

The published snapshot is from 2026-09-25 and holds neither. A merged pull request is not a
publication: kafkakn republishes its snapshot by hand (research §1.8).

- **The decision and its reason.** This item is done when the snapshot mostik resolves carries B-74's
  API. mostik does not build kafkakn from a checkout, because the published klib is what a stranger
  links (research §1.8, consequence 1).
- If kafkakn decides that B-74 cannot hold on one arm, this item records what that arm gives, and
  research open question 3 goes to the owner.

- AC: `maven-metadata.xml` for `kafkakn-core` shows a `lastUpdated` after kafkakn B-74 merged. mostik
  compiles a call to B-74's API on both builds.
- Anchors: `gradle/libs.versions.toml`.
