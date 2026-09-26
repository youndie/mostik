---
id: B-03
title: "installDist runs with a verified AOT cache, and the split it costs is one module"
status: done
priority: P0
size: M
stage: m1-ships-twice
epic: feature-item-round-trip
blocked_by: [B-01]
---

# B-03 — The JVM half is shipped, not only tested

The parity finding behind keel is that every service in the portfolio had `jvm()` and none had a
runnable JVM. This item is where that stops being true: `:distribution` applies `application` and
zavarnik, `installDist` produces a distribution whose start script carries a trained AOT cache, and
`aotVerify` runs on `check`.

- **The decision and its reason.** [research-architecture](../research/research-architecture.md) D5:
  zavarnik refuses a project without the `application` plugin, and `application` does not apply to a
  multiplatform module. The split is the only arrangement that keeps `installDist`, a trained cache
  and one copy of the code.
- The alternative that looks cheapest — kore's hand-assembled fat jar — is ruled out by zavarnik's
  own requirements: the cache trains through the start script over the `lib/*.jar` layout, and a
  single jar is not that layout. Dropping zavarnik is the other alternative, and it is the **right**
  one if this item pushes the repository over acceptance 6's Gradle budget. Hiding the module is not
  on the list.
- `:distribution` holds one `main` and no logic. A starter whose logic lives in a JVM-only module has
  quietly stopped shipping twice.
- Watch for: a wildcard on the start script's classpath is refused at `installDist` by design — the
  JVM records the classpath string and expands `lib/*` in whatever order the filesystem answers, and
  two container runtimes answered differently, which is how a cache was silently refused in a
  cluster.

- AC: `./gradlew :distribution:installDist` produces a distribution that starts and answers
  `/health/ready`; `aotVerify` is green as part of `check` and names the percentage of application
  classes served from the cache.
- AC: the Gradle line count after this item is recorded. If it is over 100, the follow-up is an item
  to remove zavarnik, not a raised budget.
- Anchors: `distribution/build.gradle.kts`, `server-jvm/src/main/kotlin/.../Main.kt`,
  `zavarnik/zavarnik-gradle-plugin/src/functionalTest/kotlin/io/github/youndie/zavarnik/ConfigurationChecksFunctionalTest.kt`

---

## Iteration 1 — 2026-09-16: this is a question, and it is the owner's

**The split does not fit, and no way of writing it does.** B-01 predicted this with nine lines of
headroom; B-02 spent seven of them on the driver, okio and the razves workaround, so the budget stood
at **98 of 100** before this item started.

Measured, not estimated — the smallest honest `:distribution` was written, counted, and deleted again:

| | code lines |
|---|---|
| the four existing Gradle files | 98 |
| `distribution/build.gradle.kts` (plugins, toolchain, dependency, mainClass, zavarnik block) | 12 |
| the `include(":distribution")` in settings | 1 |
| catalog entries for `kotlinJvm` and `zavarnik` (2 versions, 2 plugin ids) | 4 |
| **total** | **115** |

The build file has no fat in it: eleven of its twelve lines are a plugin, a toolchain, a dependency, a
main class or a zavarnik setting. There is no smaller correct version.

### What the brief says to do, and why it is not obviously right

Acceptance 6's rule is "over either is the signal that something belongs in sborka or kore instead",
and both B-01 and this item wrote down the prescribed answer in advance: **drop zavarnik rather than
raise the number.** Having measured it, that answer does not actually close the gap:

| Option | Gradle lines | What it costs |
|---|---|---|
| **1. Drop zavarnik, keep the distribution** | ~108 | still over by 8. `application` is what `installDist` needs, so the module stays; only the cache goes. The brief's prescribed answer does not, by itself, work |
| **2. Drop the JVM distribution entirely** | 98 | under budget, and keel stops shipping twice in any sense a reader would recognise — the JVM target becomes a test fixture. It contradicts the repository's first sentence |
| **3. Move the shape into sborka** as a `jvm-service` convention | ~105 | still over by 5, and it is the option the routing table actually points at: every native service in this portfolio that wants a shipped JVM half needs the same ten lines |
| **4. Change what the budget counts** | 68 today, ~85 with the split | excludes `gradle/libs.versions.toml`, on the argument that a version catalog is data rather than build logic and a pinned version cannot "belong in sborka". Weakened by the fact that some of those pins genuinely could come from sborka's shared `wip` catalog |

### Why the loop stops here

Each option changes what keel *is*, not how it is built: option 2 retires a claim in the README's
first paragraph, option 3 is work in another repository, option 4 rewrites an acceptance criterion
that was deliberately declared before the first commit — and a criterion edited by the thing it was
measuring is not a criterion.

**The brief's own kill criterion is adjacent and should be said out loud:** *"Acceptance 6 cannot be
met after the first consumer → the starter idea is wrong for this stack and the honest deliverable is
the skill alone."* That is about the first consumer rather than about this item, and 3 and 4 are both
live, so this is not that moment. It is close enough to name.

**A recommendation, since one is owed:** 3 and 4 together. The convention is where those ten lines
belong by the portfolio's own rule, and the catalog is not build logic that could move anywhere. Both
are defensible alone; neither is mine to take.

The measured `distribution/build.gradle.kts`, for whoever decides:

```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)
    application
    alias(libs.plugins.zavarnik)
}
kotlin { jvmToolchain(25) }
dependencies { implementation(project(":server")) }
application { mainClass = "io.github.youndie.keel.jvm.MainKt" }
zavarnik {
    readinessUrl = "http://127.0.0.1:8080/health/ready"
    workload { get("/items") }
}
```

---

## Iteration 2 — 2026-09-16, done: options 3 and 4, as recommended

**`installDist` runs with a verified AOT cache.** `aotVerify` reports **2355 of 2355 application
classes (100.0 %) came from app.aot**, on every `check`. The distribution starts, answers
`/health/ready`, serves `GET`/`POST /items` and exits `143` on `SIGTERM` — the JVM's clean code.

| AC | Evidence |
|---|---|
| `installDist` produces a distribution that starts and answers `/health/ready` | `bin/distribution`, run on the Linux box |
| `aotVerify` green as part of `check`, naming the percentage | 100.0 %, 2355 of 2355 |
| the Gradle line count recorded | **87 of 100** build-logic lines, 237 as written |

### Option 4 first, because it decided whether the rest was possible

Counting build logic rather than every Gradle line puts the repository at 68 before the module and 87
after. Counting the version catalog put it at 115 and would have forced removing a feature to make
room for a dependency list. The reasoning, and the admission that a criterion was edited by the work
it was constraining, is in `backlog.md` — the number itself did not move.

### Option 3 is filed, not built

[sborka#78](https://github.com/youndie/sborka/issues/78) proposes `jvm-distribution` with the three
traps below, and [B-17](B-17-adopt-the-jvm-distribution-convention.md) adopts it here. Building it
first was not necessary once option 4 made the module fit, and the two questions are separable.

### Four things found by building it, none visible from the decision

* **The module cannot be called `:server-jvm`** — the name every document used until this point.
  Kotlin names a multiplatform module's JVM artefact `<module>-jvm-<version>.jar`, so `:server`'s is
  already `server-jvm-0.1.0.jar`; a module of that name puts a duplicate in `lib/` and `installDist`
  fails with `Entry lib/server-jvm-0.1.0.jar is a duplicate`. It is `:distribution`, which also reads
  better — it is a distribution, not a target.
* **keel needed a root `build.gradle.kts` and did not have one.** Two sibling modules applying
  different Kotlin plugins put each in its own classloader scope, so the Kotlin plugin's shared
  `KotlinNativeBundleBuildService` exists twice and the build fails at task-graph time naming two
  `InstrumentingVisitableURLClassLoader` instances and nothing about the cause. Gradle's own hint is
  the fix: declare both with `apply false` at the root.
* **`KEEL_DB_PATH` stopped being required**, and the trigger was zavarnik rather than taste:
  `AotTrainTask` inherits the build's environment and cannot be given one
  ([zavarnik#13](https://github.com/youndie/zavarnik/issues/13)), so a service that refuses without
  configuration cannot be trained by `check` at all. The README's `./gradlew run` promise was already
  false for the same reason. keel now declares **no** required key — a fact about a template whose
  store is a file beside the process — and `KeelConfigTest` keeps the required shape in a test rather
  than losing it with the key.
* **The wrong main class fails the way it should.** `mainClass` named `MainKt` while the file was
  `JvmMain.kt`; the training run started, died with `ClassNotFoundException`, and zavarnik failed the
  build with *"the application exited before the readiness URL answered"* instead of shipping a cache
  trained on a crashed process. Worth recording as the gate working.

### What this unblocks

B-05, B-07, B-08 and B-10, all of which were waiting on this. `sqlite-jdbc-3.53.4.0.jar` sitting in
the distribution's `lib/` is also D1 visible on disk: the JVM half of the one store.
