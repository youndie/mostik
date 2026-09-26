---
id: keel-server
title: keel :server — the template service
type: service
repo_url: https://github.com/youndie/keel
module: ":server and :distribution — see section 3"
tech_stack: [Kotlin Multiplatform, Ktor CIO, sqlx4k-sqlite, kore, sborka, Docker]
owner: unassigned
status: active
depends_on:
  - kore
  - sborka
  - razves
  - zavarnik
  - sqlx4k-sqlite
publishes:
  - "ghcr.io/youndie/keel (the template's own image; a clone renames it)"
---

# keel `:server`

**Everything below is built, and this document was re-read against it rather than flipped.** Two
targets that run, a store that survives a restart, suites on both, an ordered shutdown verified under
load by kore's oracle, a distribution with a verified AOT cache, an image, and a measurement taken on
two hosts. `status` is `active` because of that re-reading — B-10 — and the four things it corrected
are worth knowing, because each was a sentence that had quietly stopped being true:

* §2a named `server/src/linuxX64Main/.../Main.kt`; the file is in `nativeMain`, so that enabling
  `keel.linuxArm64` needs a property rather than a second copy of it;
* §2a and §6 offered `--build-arg STATIC=1`, which B-16 decided not to ship — a reader following §6
  would have built the ordinary image and believed it was the static one;
* §5 said B-16 *asks* whether to ship it. B-16 answered;
* `k6/measure.sh` existed and was in no anchor table.

What is **not** here and says so: a `scratch` image (B-16, by decision), `linuxArm64` coverage (B-15),
and an automated stand run (B-13 — the measurement was taken by hand).

What *is* verified rather than merely built is
[research-architecture](../research/research-architecture.md) §1: everything keel depends on was read
in a published artefact or a portfolio repository on 2026-09-16, and §1.3 and §1.4 gained a
confirmation from B-01's first build.

## 1. Responsibility

One Kotlin Multiplatform module that is a **complete, runnable server on two targets** and carries no
domain: one entity `Item { id, name }`, one route group, one storage port, three probes, `/version`,
an ordered shutdown, an image, and a measurement harness. A clone renames it and starts writing
features.

What it deliberately does **not** do:

- **no client.** kompot and Compose belong to a second template. Mixing them is how konekt reached
  300 pages;
- **no second entity.** A starter whose example grows features stops being a starter. `Item` stays
  `{ id, name }`;
- **no build logic of its own.** Every convention is sborka's; keel's build files apply conventions
  and set names. A flag that appears in keel's `build.gradle.kts` is a flag sborka forgot, and it is
  filed there rather than fixed here (§3, the routing table);
- **no lifecycle code.** `announce → drain → release → exit`, the three probes and the typed
  configuration are kore's. keel shows them wired and adds nothing;
- **no documentation of the stack.** How kore or sborka work is documented where they live. This tree
  is keel's own service and nothing else.

## 2. API contracts

* **Routes:** [endpoint-items](../api/endpoint-items.md) — the complete reference, keel's own route
  group and everything kore mounts.
* **Contracts:** `server/src/commonMain/kotlin/.../item/Item.kt`, a `@Serializable` data class.
  Fields are not copied into any document; the path is.
* **Auth tiers:** there are none. keel ships no authentication, and that is a non-goal rather than an
  omission — a starter that ships a half-authentication is a starter whose first consumer inherits
  it. The `ktor-server-feature` skill is where a real one comes from.

## 2a. Code anchors

| File | What is there |
|---|---|
| `server/build.gradle.kts` | targets, the two sborka conventions, `nativeService { }` **before** the target block |
| `server/src/commonMain/kotlin/.../KeelMain.kt` | everything both entry points do: `--print-config`, the build line, the configuration read, then start |
| `server/src/commonMain/kotlin/.../Wiring.kt` | `embeddedServer(CIO)`, `installKoreProbes`, `installKoreVersion`, `runUntilSignal` with the four registrations |
| `server/src/commonMain/kotlin/.../KeelConfig.kt` | the four `ConfigKey`s and the `ConfigSchema` |
| `server/src/commonMain/kotlin/.../item/ItemStore.kt` | the port, the schema and `SqliteItemStore` — one implementation, both targets |
| `server/src/commonMain/kotlin/.../item/ItemRoutes.kt` | `GET`/`POST /items` |
| `server/src/jvmMain/kotlin/.../Main.kt`, `server/src/nativeMain/kotlin/.../Main.kt` | four lines each; the only thing that differs between the two builds. The native one is in `nativeMain` rather than `linuxX64Main` so that turning `keel.linuxArm64` on is a property and not a second copy of the file |
| `build.gradle.kts` | the root, and it exists for one reason — two Kotlin plugins in one build |
| `distribution/build.gradle.kts` | `application` + zavarnik, and the reason it exists (§3) |
| `distribution/src/main/kotlin/.../jvm/Main.kt` | one line; anything that grows here belongs in `:server` |
| `Dockerfile` | two stages; one runtime image, and no static variant — B-16 |
| `k6/items.js` | the scenario both binaries are driven with; `KEEL_MEASURE=1` gives it a constant-work profile |
| `k6/measure.sh` | the three numbers, and the refusal to write them without a stand |
| `.github/workflows/check.yaml` | the documentation gate and the build gate |

## 3. How it is built

**The module split, and why there are two.** `:server` is the multiplatform module and holds every
line of Kotlin that matters. `:distribution` is `kotlin("jvm")`, applies `application` and zavarnik,
and contains one `main` that calls into `:server`'s JVM target. It exists because `application` and
zavarnik are `kotlinJvm`-only and do not apply to a multiplatform module —
[research-architecture](../research/research-architecture.md) D5.

Three things about it are not obvious and each cost a red build:

* **it is not called `:server-jvm`**, which is what every document called it until it was built.
  Kotlin already names `:server`'s JVM artefact `server-jvm-0.1.0.jar`, so a module of that name puts
  a duplicate in the distribution's `lib/` and `installDist` refuses;
* **the root `build.gradle.kts` exists only for this.** Two sibling modules applying different Kotlin
  plugins need both declared there with `apply false`, or each lands in its own classloader scope and
  the Kotlin plugin's shared build service exists twice. The failure names two classloaders and
  nothing about the cause;
* **most of it is not keel's any more.** `sborka.jvm-distribution` carries `application`, the JDK
  floor, zavarnik's readiness default and the collision guard above; what is left here is the main
  class and the training workload. B-17 — which bought **no lines at all**, and three decisions a
  clone can no longer get wrong.

**The two allocators, one floor below the other.** `sborka.native-service` sets
`fixedBlockPageSize=16` on the binary; the image sets `MALLOC_ARENA_MAX=2`. Kotlin/Native's allocator
keeps a page per size class *per thread* and a thread holds its pages for as long as it lives, so
resident memory follows the thread count rather than the live heap — which is why a service dies at a
limit its heap is nowhere near. Neither number is keel's to change without a measurement, and the
pair is dangerous in one specific combination: with `-Xallocator=std`, `MALLOC_ARENA_MAX=2` took a
peak from 39.3 MB to 413.7 MB and 10 survivals out of 10 to 7. Both lines carry that beside them.

**Where the binary lands.** `stageNativeImage` puts the release `.kexe` at
`server/build/native-image/<baseName>` whatever the target was declared as, and writes a
`<baseName>.needed.txt`
next to it naming what the binary asks the loader for. The `Dockerfile` copies from that path and
nothing else; the whole point of the convention is that a `COPY` line survives being moved between
repositories.

**The order things run in at startup**, and it is a claim about what a service owes an operator:
`--print-config` answers before anything else including the build line, because it is asked *because*
the process will not start; the configuration is read once before anything serves; a refusal prints
the message and nothing else, because a stack trace buries the two lines that say which variable and
why.

**The order things run in at shutdown** is kore's and is the product:
`announce` (readiness false, then a wait long enough to matter) → `drain` (accept stops, in-flight
finishes, new arrivals get 503) → `release` (the store, then telemetry, each with its own deadline)
→ `exit`. keel registers, and registers nothing in `ApplicationStopping` — §8, first quirk.

**Where a defect goes**, which is the rule that keeps keel from becoming konekt:

| the line was | it goes to |
|---|---|
| a build flag, a linker option, a CI step | sborka |
| lifecycle, probes, config, shutdown | kore |
| a procedure the agent had to work out | the `native-service-bootstrap` skill |

Nothing goes to keel except renaming.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Library | `io.github.youndie:kore-core`, `kore-ktor` | the shutdown sequence, the probes, the typed config, `/version`. `0.1.4` on the portfolio's repository |
| Gradle | `io.github.youndie.sborka.native-service`, `.kmp`, `.lint`, `.settings` | the binary's name and staging, `fixedBlockPageSize`, `--as-needed`, ktlint. `0.4.0.79` |
| Gradle | `io.github.youndie.razves` | the size budget `sborka.binaryBudget` is enforced by; the convention fails configuration if the property is set and this is absent |
| Gradle | `io.github.youndie.zavarnik` | the AOT cache for the JVM distribution, and `aotVerify` on `check` |
| Database | `io.github.smyrgeorge:sqlx4k-sqlite:1.13.1` | the one store, on both targets — Rust driver on native, `org.xerial:sqlite-jdbc` on the JVM |
| Tool | k6 | the load scenario for the parity smoke and `:server:measure` |
| Optional | `io.github.youndie.chronik:chronik-core` | a durable timer; commented out, `linuxX64` only |

**Only sqlx4k-sqlite is on Maven Central.** kore, sborka and razves resolve from
`https://reposilite.kotlin.website/snapshots`, and until they reach Central a clone needs that
repository configured — which is acceptance 1's precondition and the README's first block.

## 5. Infrastructure and deploy

* **Image:** built from the repository root, two stages, runtime `gcr.io/distroless/cc-debian13`.
  **13 972 497 bytes**, measured 2026-09-16 with `docker image inspect` on `linux/amd64` — the method
  is named because `docker images` reports 55.4MB for the same image, counting every platform of the
  base manifest. **There is no `STATIC=1` variant, by decision**: the `scratch` recipe pins five
  `konan.properties` keys JetBrains may change in any patch release, and a template is copied and
  never updated again — [B-16](../backlog/B-16-static-image.md), with
  [B-18](../backlog/B-18-scratch-when-static-is-static.md) as its expiry. The recipe is written down
  in the research; it is not shipped.
* **`.dockerignore` does not exclude `.git`**, deliberately. `/version` is served from an identity the
  Gradle plugin reads out of git at build time, so excluding the directory — the obvious thing to do
  for context size — answers `0.1.0+unknown` in the artefact where the question matters most.
* **Probes:** `GET /health/startup`, `GET /health/ready`, `GET /health/live`. A chart must point
  readiness at `/health/ready` and **not** at `/health`, which is an alias for liveness — see §8.
* **Version:** `GET /version`, `key: value` per line, read by deploy checks rather than by people.
* **`ENTRYPOINT` in exec form, always.** Shell form makes `/bin/sh -c` PID 1 and it does not forward
  `SIGTERM`, so the process never sees the signal and the run looks like an instant clean shutdown.
* **No chart.** keel ships no Helm; the chart is a decision about a cluster keel does not have. The
  `native-service-bootstrap` skill carries one.

## 6. Local setup

```bash
./gradlew :distribution:run                     # the JVM half; works on a fresh clone, no config
./gradlew :server:linkReleaseExecutableLinuxX64 # the native binary; Linux only
./gradlew :server:measure                       # three numbers, and a refusal to write them
docker build -t keel .                          # the only image there is
```

Nothing else has to be running: the store is SQLite in a file, and `KEEL_DB_PATH` defaults to one
under the working directory.

**Both binaries cannot be built on a Mac.** A klib cross-compiles and an executable does not — a Mac
cannot produce an ELF. On a mutagen-synced checkout the Gradle commands run on the Linux box
(`~/.claude/bin/wsl-run ./gradlew …`); `make check` stays local.

## 7. Configuration

Four keys, declared in `server/src/commonMain/kotlin/.../KeelConfig.kt` under the prefix `KEEL`. The
list is not copied here — `--print-config` prints every value with its origin, and a copy in this
document would be the second schema that disagrees with the first.

```bash
./gradlew :distribution:run --args="--print-config"
```

**keel declares no required key**, and that is a fact about a template rather than a lesson. Each key
is still a different *shape*, so a clone deletes what it does not need and keeps an example:

| Key | Shape | Why that shape |
|---|---|---|
| `KEEL_DB_PATH` | a **default** (`keel.db`) | it was required until B-03; see below |
| `KEEL_PORT` | a **default** (8080) | a value a deployment should not have to repeat; `--print-config` still prints `DEFAULT` beside it |
| `KEEL_TRACY_ENDPOINT` | **optional**, half of a pair | unset means "not observed", which is a decision |
| `KEEL_TRACY_KEY` | **optional and secret**, the other half | masked by the declaration rather than by a list somebody keeps in sync |

This section used to say "two have defaults", which was never true of the code — it described kore's
sample, which the schema was modelled on and which has a `WORK_MS` keel does not. Nothing noticed
until B-06 wrote a test against the schema and had to count the keys.

**`KEEL_DB_PATH` was required and is not, decided in B-03.** The argument for requiring it — a service
that invents where its data lives starts happily and serves wrong data — is right for a service whose
database is somewhere else and wrong for a template whose store is a file beside the process. Two
things made it false as written: the README promises `./gradlew run` works on a fresh clone, and
zavarnik's training run inherits the build's environment and cannot be given one
([zavarnik#13](https://github.com/youndie/zavarnik/issues/13)), so a service that refuses without
configuration cannot have its AOT cache trained by `check`. The required shape is kept in
`KeelConfigTest` rather than lost with the key: a real service's required key is a database address or
a credential.

## 8. Quirks

Twenty, and the first five are not keel's: they are the platform divergences every Kotlin/Native
Ktor service inherits, verified by kore against the artefacts rather than against documentation
([research-architecture](../research/research-architecture.md) §1.2). They are here because a keel
reader will not have kore's research open, and each one looks like a bug in the service.

1. **`ApplicationStopping` runs after the drain on the JVM and before it on Kotlin/Native**, from
   identical source, with nothing saying so. Every Ktor example tells you to close your pool there.
   keel closes nothing there; the store is a release-stage participant.
2. **`ApplicationStopPreparing` fires after the socket has stopped accepting** on CIO, so it cannot
   flip readiness — which is the one thing its name suggests.
3. **The Kotlin/Native shutdown hook is a single global slot**, last registration wins, and the
   callback runs on the POSIX signal-handler stack. Never call `addShutdownHook`; kore installs a
   handler that writes a flag and nothing else.
4. **`Connection: close` on a response does not close a CIO connection** — the engine reads keep-alive
   from the *request's* header. kore promises the header and not the socket, and so does keel.
5. **Enumerating the environment is `__environ` on Linux and does not exist on macOS native**, so the
   unknown-variable check is a declared capability rather than a universal one. A capability that is
   absent on a target says so; it never reports "nothing found" where the check could not run.

And keel's own:

6. **A clean `SIGTERM` exits `0` on Kotlin/Native and `143` on the JVM.** Both are right. Never assert
   a specific exit code across the two; assert the process ended itself and was not `SIGKILL`ed
   (`137`). The parity normaliser has this as its first entry.
7. **`/health` is liveness, not readiness.** It is an alias kore mounts because every chart in the
   portfolio already names it, and a chart pointing readiness there gets a probe that cannot fail
   while the process is alive — which is the failure the three probes exist to stop.
8. **`runUntilSignal`'s default `watch` argument installs a signal handler when the call is made**, so
   the call belongs *after* `server.start(wait = false)`. Installed earlier, it catches a signal whose
   sequence has nothing to drain. Nothing at the call site shows this.
9. **The shutdown transcript is printed inside `onFinished`, not after the call.** On the JVM,
   `runUntilSignal` returning means the shutdown hook has returned and the process is already on its
   way out; the line after the call never runs. kore shipped this defect in its own example and a
   consumer found it.
10. **Two sqlx4k drivers in one native binary do not link.** Not a resolution failure — a link failure
    naming a Rust symbol, `duplicate symbol: std::panicking::EMPTY_PANIC`. keel takes exactly one
    driver; a clone adding Postgres **replaces** SQLite rather than adding to it.
11. **The chronik timer block is `linuxX64` only.** Turning it on together with `keel.linuxArm64=true`
    fails at resolution with "no matching variant", which names an attribute and not the decision
    that caused it.
12. **`writeNativeDockerfile` refuses to overwrite.** keel's `Dockerfile` is committed, so the task
    will always refuse here; it is for a clone that deleted the file, and the refusal is deliberate —
    the runtime image is where certificates, shared libraries and a base image's glibc are decided.
13. **A green `build` on one host does not mean both targets were tested.** Kotlin/Native has no
    `linux_arm64` host, so `linuxArm64Test` is never *created* — it does not appear as skipped, it does
    not appear at all. CI links there and executes the test binary on an arm64 runner; a local green
    build proves nothing about it. Since B-06, CI names `jvmTest` and `linuxX64Test` and fails when
    either produces no result file or reports zero tests — `find | wc -l` would pass while one target
    quietly stopped being wired, which is the brief's red list exactly. Since B-15 `linuxArm64` is
    covered too: the x86-64 job cross-links the test binary and an `ubuntu-24.04-arm` runner executes
    it. Since B-21 `build` with that target on works too — sborka#80 fixed in `0.4.0.82`, one staged
    binary per target, and quirk 19 for what that does to the `COPY` line.
14. **keel's startup probe answers `200` immediately, and that is correct rather than broken.** A
    `StartupGate` with no named gates is started from birth — `started = gates.isEmpty()` — and keel
    names none, so `/health/startup` says "started" from the moment the module is installed. The probe
    only means something once a service names what it is waiting for, so a clone that adds migrations
    without adding a gate has a startup probe that lies. `StartupGate(gates = setOf("migrations"))`
    and `completed("migrations")` are the two lines that fix it, and `ItemRoutesTest` guards both
    halves so the behaviour is written down where someone will meet it.
15. **The store suite cannot see a database that is not on disk, and one test exists only for that.**
    Every case that writes through the store and reads back through it passes just as well against a
    database living only in that process — which the service shipped for the length of one build,
    answering every request correctly and coming back empty after a restart. The cause was a broken
    string template, so the URL named the expression instead of the path.
    `the rows survive the driver being closed and reopened` is the one case that would have caught it,
    and `KeelDatabaseUrlTest` guards the line itself.
16. **The size budget is off for the debug binary, and that is a workaround with an address:
    [razves#4](https://github.com/youndie/razves/issues/4).** razves applies one `budget` to every
    executable; debug is 28,580,560 against release's 9,227,448, so one number cannot watch both. The
    release check keeps the real 25 MiB — `stageNativeImage` stages that binary and the image carries
    it — and the debug check is disabled in `server/build.gradle.kts`. It is the only line in this
    repository's build files that is not "apply a convention and set a name".
17. **`KEEL_DB_PATH` through a directory that does not exist fails with a raw JDBC stack trace.**
    `mode=rwc` creates the database *file*, not its parent, so a path like `/var/lib/keel/keel.db` on
    a host where `/var/lib/keel` is absent dies at startup in `org.sqlite.core.DB.open` with nothing
    said about the directory. A deployment mounting a volume creates the directory; a `--build-arg` or
    an `environment(...)` pointing somewhere new does not. Found by pointing the AOT training run at
    `build/tmp/aot-train/`, which did not exist.
18. **`GET /items` returns the whole table, and every clone inherits that.** There is no limit, no
    cursor and no page. It is fine for a template whose example holds a handful of rows and it is not
    fine in a service: B-13's first stand run drove it at 500 req/s and got **29.8 iterations a
    second, 4 692 dropped and 151 MB in ten seconds**, all of it a response body growing by 500 rows
    a second. The measurement profile skips it (`KEEL_MEASURE=1`); the parity and smoke runs keep it,
    because there the body is the point. A clone that keeps this route past its first thousand rows
    has a denial of service it wrote itself.
19. **Turning `keel.linuxArm64` on moves the staged binary, and the `Dockerfile` does not follow.**
    `stageNativeImage` stages flat under one native target and per-target under two —
    `build/native-image/linux_x64/keel` — which is correct, because one name for two binaries is a
    `COPY` that finds the wrong file. keel ships with the property off so the committed `COPY` is
    right; a clone that turns it on edits that line, and the `Dockerfile` says so at the line itself.
    The failure otherwise arrives at image build time as "not found", naming the path and nothing
    about the property that moved it.
20. **A Gradle task that writes into the repository must not be run through the replica.** The mutagen
    session is a one-way replica, so `./gradlew updateEditorconfig` on the Linux box wrote
    `.editorconfig` there and the next sync deleted it. Generated files arrive on the Mac or not at
    all.
