---
id: B-20
title: "Give the training run its own database path, now that zavarnik can"
status: done
priority: P2
size: S
stage: m1-ships-twice
epic: feature-item-round-trip
---

# B-20 — The training run no longer has to borrow the service's defaults

[zavarnik#13](https://github.com/youndie/zavarnik/issues/13) is fixed: `training { environment(...) }`
gives the AOT training run variables of its own, and `0.1.0.41` carries it.

That matters here for two things keel decided *because* the gap existed.

- **Quirk 17 can go.** The training run creates its database wherever `KEEL_DB_PATH` resolves, which
  with a relative default is inside `build/install/distribution` — so a clone whose Dockerfile copies
  `installDist` after `check` ships the training run's data. Pointing the training run at
  `build/tmp/aot-train/` removes the trap rather than documenting it.
- **The `KEEL_DB_PATH` default keeps one of its two reasons, and loses the other.** B-03 made the key
  optional because (a) zavarnik could not train a service that refuses without configuration, and
  (b) the README promises `./gradlew run` works on a fresh clone. (a) is gone. **(b) stands on its
  own**, so the decision does not change — but the documentation must stop citing a constraint that
  no longer exists, because the next reader who checks zavarnik will find it fixed and reasonably
  conclude the decision was stale.

- AC: `check` is green and the AOT cache still verifies at 100 %.
- AC: no database is left in `build/install/distribution` after a full build.
- AC: quirk 17 is deleted **after** that is verified, not on the strength of the bump.
- AC: every place that cites zavarnik#13 as a live constraint says what is actually true now.
- Anchors: `distribution/build.gradle.kts`, `gradle/libs.versions.toml`,
  `server/src/commonMain/kotlin/.../KeelConfig.kt`

---

## Iteration 1 — 2026-09-16, done

zavarnik `0.1.0.41` taken; the training run gets `KEEL_DB_PATH` of its own.

| AC | Evidence |
|---|---|
| `check` green, the cache still verifies | **2352 of 2352 application classes (100.0 %)** from `app.aot` |
| no database left in `build/install/distribution` | `bin` and `lib`, nothing else |
| quirk 17 deleted after verifying | done — and **replaced**, see below |
| every citation of zavarnik#13 says what is true now | `KeelConfig`'s KDoc and research D5 |

### The line cost one attempt, and the failure is worth more than the fix

Pointing the training run at `build/tmp/aot-train/keel.db` failed: `mode=rwc` creates the database
**file**, not its parent directory, so the service died at startup inside `org.sqlite.core.DB.open`
with a JDBC stack trace that says nothing about a directory.

That is not a fact about the training run. **It is how keel behaves anywhere `KEEL_DB_PATH` points
through a directory that does not exist** — a volume whose mount point a chart forgot to create, an
operator setting a path by hand. So quirk 17 was not deleted so much as **replaced**: the old one
described a trap a clone could hit by copying `installDist`, and the new one describes one any
deployment can hit.

### What did not change, and why that is the point

`KEEL_DB_PATH` stays optional. B-03 gave two reasons and only one has gone: zavarnik could not be
given an environment (fixed), and the README promises `./gradlew run` works on a fresh clone (still
true). **The documentation had to change even though the decision did not** — anyone checking
zavarnik#13 now finds it closed, and would reasonably conclude the decision was stale if both reasons
were still presented as live.
