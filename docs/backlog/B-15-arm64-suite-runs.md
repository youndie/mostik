---
id: B-15
title: "Run the linuxArm64 suite on an arm64 runner, once razves can register its tasks"
status: done
priority: P2
size: S
stage: m1-ships-twice
epic: feature-item-round-trip
---

# B-15 — The third target is built by nobody and tested by nobody

B-06 gave CI a build job that names `jvmTest` and `linuxX64Test` and fails when either is missing.
`linuxArm64` is not in that list, because it is off by default (`keel.linuxArm64`) — and because
turning it on does not currently build at all.

- **Blocked by [youndie/razves#3](https://github.com/youndie/razves/issues/3).** With two native
  targets razves registers `sizeReportDebugExecutable` twice — its task names carry the build type
  and not the target — and the build fails at configuration with "Cannot add task
  'sizeReportDebugExecutable' as a task with that name already exists". So `-Pkeel.linuxArm64=true`
  cannot be built while `sborka.binaryBudget` is set, which is every build of this repository.
- **The decision and its reason.** The shape is kore's, and it is not the obvious one: the x86-64 job
  **cross-links** the test binary and an `ubuntu-24.04-arm` runner **executes** it. Kotlin/Native has
  no `linux_arm64` host, so `linuxArm64Test` is never created — it does not appear as skipped, it
  does not appear at all — but linking and running are separable even where the plugin will not do
  it for you.
- The rejected alternative is building everything on an arm64 runner. It works and it doubles the
  Kotlin/Native toolchain downloads, for a target that is off by default.
- Not covered: making `linuxArm64` a default target. That is a decision about what keel ships, not
  about what CI checks, and nothing deploys there yet.

- AC: a `linux-arm64-suite` job executes the cross-linked binary on `ubuntu-24.04-arm`, fails if no
  binary was produced, and fails if the binaries ran zero tests.
- AC: `-Pkeel.linuxArm64=true ./gradlew build` is green, which is what razves#3 has to land for.
- Anchors: `.github/workflows/check.yaml`, `server/build.gradle.kts`, `gradle.properties`

---

## Iteration 1 — 2026-09-16, done for the suite; the full build is a different item's problem

[razves#3](https://github.com/youndie/razves/issues/3) landed in **0.1.0.31**: the size tasks carry the
target (`sizeReportLinuxX64DebugExecutable`), so two native targets configure. Verified by taking the
bump, not by reading the issue.

| AC | |
|---|---|
| a `linux-arm64-suite` job executes the cross-linked binary on `ubuntu-24.04-arm`, failing if none was produced and if it ran zero tests | done |
| `-Pkeel.linuxArm64=true ./gradlew build` is green | **no** — see below |

The binary cross-links on x86-64: 24 600 496 bytes, `ELF 64-bit LSB executable, ARM aarch64`. CI
uploads it and an arm64 runner executes it, because Kotlin/Native has no `linux_arm64` host and so
`linuxArm64Test` is never *created* — not skipped, not disabled, absent.

### Why the second criterion is not met, and why that is not this item waiting

`./gradlew build -Pkeel.linuxArm64=true` fails in `stageNativeImage`: it stages every release `.kexe`
under one name, so two targets collide — `Entry keel is a duplicate`. Filed as
[sborka#80](https://github.com/youndie/sborka/issues/80).

**`duplicatesStrategy` is not the workaround**, and that is worth stating because it is one line and
it is tempting. It would stage one of the two binaries, chosen arbitrarily, under a name that says
nothing about which — and a `COPY` that finds the wrong file is worse than one that fails, which is
what that task's own comment is written against.

**The suite never needed it.** `linkDebugTestLinuxArm64` does not go through staging, so what this
item was actually for — a suite that runs on the architecture nobody was testing — is done. The full
`build` with the third target on is a packaging question.

**Closed the same day by [B-21](B-21-build-with-both-native-targets.md):** sborka#80 was fixed and
published as `0.4.0.82`, `build -Pkeel.linuxArm64=true` is green, and the staged layout's change of
shape is handled at the `Dockerfile`'s `COPY` line where somebody will read it.
