---
id: B-17
title: "Adopt sborka's jvm-distribution convention once it exists"
status: done
priority: P3
size: XS
stage: m1-ships-twice
---

# B-17 — Twelve lines that belong in sborka

`distribution/build.gradle.kts` is twelve lines of build logic, and only three of them are keel's:
the main class, the project it depends on and the workload. The rest is what *any* Kotlin/Native
service in this portfolio needs to ship a JVM half, which is the definition of something belonging in
a convention rather than in a repository.

Proposed as [sborka#78](https://github.com/youndie/sborka/issues/78), with the three traps it would
carry that cost keel a red build each: the root build's `apply false` for two sibling Kotlin plugins,
the module name that must not be `:distribution`, and the JDK 25 floor.

- **The decision and its reason.** B-03 chose to fit the module inside the budget rather than wait for
  the convention, because the budget question and the convention question are separable and only one
  of them blocked five items. This is the other half, and it is `P3` because nothing is broken — the
  file works, it is just in the wrong repository.
- Not covered: writing the convention. That is sborka's work, in sborka's backlog.

- AC: `distribution/build.gradle.kts` applies `io.github.youndie.sborka.jvm-distribution` and is down
  to its three keel-specific lines; `./gradlew build` and `aotVerify` stay green.
- AC: the build-logic count in `backlog.md` is re-measured and the new number recorded.
- Anchors: `distribution/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`

---

## Iteration 1 — 2026-09-16, done, and the line count did not move

[sborka#78](https://github.com/youndie/sborka/issues/78) landed as `sborka.jvm-distribution` in
**0.4.0.81**. `distribution/build.gradle.kts` applies it, `./gradlew build` is green and `aotVerify`
still reports **2352 of 2352 application classes (100 %)** from the cache.

**Build logic is 95 of 100 — exactly what it was before.** The AC expected the module "down to its
three keel-specific lines", and it is: `mainClass` and the training workload. But what the convention
took over was replaced almost line for line by what it takes to ask for it:

| gone | arrived |
|---|---|
| `application` | `alias(libs.plugins.sborkaJvmDistribution)` |
| `kotlin { jvmToolchain(25) }` | — |
| `application { mainClass = … }` | `jvmDistribution { mainClass = … }` |
| `readyWhen.url("…/health/ready")` | — (the convention's default) |

Two lines out, one in, and a catalog entry that does not count. **So this item bought no room**, and
saying so matters because B-03 chose to fit the module inside the budget rather than wait for this
convention, on the reasoning that the two questions were separable. They were — and this is the
evidence that waiting would have bought nothing either.

### What it did buy, which is not lines

Three decisions a clone can no longer get wrong, because they are no longer in a file a clone edits:

* **the module-name collision guard.** `:server-jvm` produces a jar named exactly like `:server`'s JVM
  artefact and `installDist` fails on the duplicate — keel found that by hitting it in B-03. The
  convention now refuses the name with a message, so the next service reads a sentence instead of
  debugging a `Copy` task;
* **the JDK floor**, which zavarnik needs at 25 and which a clone would otherwise set by hand;
* **zavarnik's readiness default**, which was a URL keel had to know and now is one it can override.

A convention that removes a decision is worth more than one that removes a line, and this item is the
clearest example of the difference that keel has produced.
