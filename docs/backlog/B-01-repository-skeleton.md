---
id: B-01
title: "The repository builds both targets with the conventions applied and nothing of its own"
status: done
priority: P0
size: M
stage: m0-shape
epic: feature-item-round-trip
---

# B-01 — The repository builds both targets, on borrowed conventions

There is no repository yet. What this item produces is the skeleton every later item stands on:
`settings.gradle.kts` applying `io.github.youndie.sborka.settings`, `:server` applying
`sborka.kmp` + `sborka.lint` + `sborka.native-service`, `jvm()` and `linuxX64()` declared with
`linuxArm64()` behind `keel.linuxArm64`, and one route answering.

- **The decision and its reason.** Every convention comes from sborka and every lifecycle call from
  kore, so keel's build files set names and apply plugins and nothing else. The test for any line
  added here is the brief's: *did konekt or katcher need this?* — and if the answer is yes but the
  line is a build flag, it belongs in sborka, not in this file.
- **`nativeService { }` goes above the `kotlin { }` block.** The convention configures
  `binaries.executable` from inside `targets.withType(...).configureEach`, which fires the moment
  `linuxX64()` declares one; a block further down is a value set after it was read, and the build
  fails with "property entryPoint has no value available", naming neither the ordering nor the place.
- The rejected alternative is a build file that declares `binaries.executable` itself. It works, and
  it is two blocks configuring one container, with the winner decided per repository — which is
  exactly what the convention exists to stop.
- This item does **not** cover the store (`B-02`), the JVM distribution (`B-03`) or the image
  (`B-04`). One route returning a constant is enough to prove the toolchain.

- AC: `./gradlew build` produces a JVM artefact and a `linuxX64` executable; `sborka.binaryBudget`
  is set in `gradle.properties` and razves is applied, so the budget is enforced rather than
  declared; `stageNativeImage` writes `server/build/native-image/<baseName>` and logs what the binary
  asks the loader for.
- AC: the Gradle line count across the repository is recorded in the commit message. Acceptance 6's
  budget is 100 lines and this is the first reading of it.
- Anchors: `settings.gradle.kts`, `gradle.properties`, `server/build.gradle.kts`,
  `sborka/build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/native-service.gradle.kts`

---

## Iteration 1 — 2026-09-16, done

`./gradlew build` is green on the Linux box with no flags. Every acceptance criterion was read off an
artefact rather than off `BUILD SUCCESSFUL`:

| AC | Evidence |
|---|---|
| a JVM artefact | `server/build/libs/server-jvm-0.1.0.jar` |
| a `linuxX64` executable | `server/build/bin/linuxX64/releaseExecutable/keel.kexe`, 4 983 240 bytes |
| `stageNativeImage` writes the staged binary | `server/build/native-image/<baseName>` plus its `.needed.txt` |
| the budget is enforced, not declared | `razves: keel.kexe: file size 19,959,384, 6,255,016 under a budget of 26,214,400` |
| line counts recorded | Gradle **91** code lines (225 as written); Kotlin **140** (275) |

### What the build confirmed

**`--as-needed` works, read off a binary outside sborka for the first time.** `keel.needed.txt` lists
**seven** shared libraries — `libm`, `libpthread`, `librt`, `libdl`, `libgcc_s`, `libc`,
`ld-linux-x86-64` — where a Kotlin/Native binary names ten by default. `libresolv`, `libutil` and
`libcrypt` are gone, which is precisely what lets the runtime image carry the binary and nothing
beside it. Recorded in research §1.3.

### What the build refuted, and where it went

**The configuration cache is incompatible with `sborka.native-service`.** `stageNativeImage` cannot
be stored — its two `project.provider { }` blocks capture the script object — and the build fails
with "cannot serialize Gradle script object references", naming the task and nothing about the
convention. keel is the first build anywhere to meet it: sborka's stand applies the convention
without the cache, and katcher, metrik and tracy run with the cache and hand-write their native
builds. Filed as [sborka#76](https://github.com/youndie/sborka/issues/76) per the routing table;
`gradle.properties` sets the property to `false` with a comment naming the issue, so the line is
deleted when it lands rather than inherited by every clone.

### Two findings that belong to later items

**Acceptance 6 had no definition, and the two readings differ by 2.5×.** 91 code lines against 225 as
written. The measure is now code lines, with both reported at every reading, and the reasoning is in
`backlog.md`. **Gradle is at 91 of 100 with one module**, so B-03's `:distribution` arrives against nine
lines of headroom — which is the brief's signal working, and the answer it prescribes is dropping
zavarnik rather than raising the number.

**`build` is green having run zero tests.** There is no test in the repository. That is not a defect
of this item, which claimed none, but it is exactly the green-build-that-visited-nothing B-06 exists
for, and it is true of the default branch until B-06 closes.

### Two things about the environment, since neither was written down

The repository had **no GitHub remote and no mutagen session** when the iteration started; both were
created with the owner's approval. The session is a **one-way replica**, and that has a consequence
worth the line: `./gradlew updateEditorconfig` run through `wsl-run` wrote `.editorconfig` on the
Linux box and the next sync deleted it. A generated file has to arrive on the Mac. `keel-server.md`
quirk 15.
