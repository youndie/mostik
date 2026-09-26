---
id: research-architecture
title: keel — architecture research
type: research
status: active
date: 2026-09-16
---

# Research: the architecture of keel

keel is a GitHub template repository for a Kotlin server that ships twice — one source, a JVM
distribution and a Kotlin/Native binary, both runnable, both tested, one image. It is
[konekt](https://github.com/youndie/konekt) with the domain removed: the wiring a new service needs
and nothing the last one happened to have. It is not a library and publishes nothing; it is not a
framework, because [kore](https://github.com/youndie/kore) owns the lifecycle and
[sborka](https://github.com/youndie/sborka) owns the build, and keel only shows them wired.

The brief is [source-brief-keel.md](source-brief-keel.md). This document records **verified facts**
(read in a published artefact, in a portfolio repository's source, or in a registry listing on
2026-09-16), **decisions** — including the four the brief left open, two of which came out
differently from what it assumed — and **risks with the machinery that mitigates them**. Anything
unverified says it is a hypothesis and names where it is settled.

**Nothing in keel is built yet.** Every document in the other three layers is `status: draft` and
describes what the repository will contain; this one describes what was checked before any of it is
written. The one thing that would make the tree dishonest is a fact here without an address, so
every row of §1 carries one.

---

## 1. Verified facts

### 1.1 kore's public surface is the shape keel wires, and it is published

Read in the working tree of `youndie/kore` at `795907e`, and in the registry listing for the
artefacts a clone would resolve.

| Fact | Where verified |
|---|---|
| `installKoreProbes(startup, readiness, liveness)` mounts `/health/startup`, `/health/ready`, `/health/live` and the alias `/health` | `kore/kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/ProbeRoutes.kt` |
| `/health` is an **alias for liveness**, not readiness — a chart pointing readiness there gets a probe that cannot fail while the process is alive | same file, the `KoreRoutes.HEALTH` route |
| `installKoreVersion(identity, release, reduced)` mounts `/version` as `key: value` lines, and **refuses to start** when `reduced` is on and the release still names the commit | `kore/kore-ktor/src/commonMain/kotlin/io/github/youndie/kore/ktor/VersionRoute.kt` |
| `runUntilSignal(deadlines, watch, onFinished, register)` is `suspend`, installs the signal watch **as a side effect of the default argument**, and takes the registrations in a builder | `kore/kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/RunUntilSignal.kt` |
| the stages are `announce → drain → consumer → pool`, registered in that order | `kore/samples/service/src/commonMain/kotlin/io/github/youndie/kore/sample/KoreWiring.kt` |
| `ConfigKey` has `required` / `string` / `optional` / `secret` / `int` / `millis`, and `ConfigSchema(prefix, keys, pairs)` collects **every** problem rather than failing on the first | `kore/kore-core/src/commonMain/kotlin/io/github/youndie/kore/config/ConfigKey.kt`, `.../config/ConfigSchema.kt` |
| `kore-core` is published at `0.1.4` | `https://reposilite.kotlin.website/snapshots/io/github/youndie/kore-core/maven-metadata.xml`, read 2026-09-16 |

**Consequence — keel's own Kotlin is wiring and one route, and acceptance 6 is plausible.** The
sample in kore already spends its lines on being an experiment (a control arm, a `FragileResource`,
a `--print-config` path); keel needs the wiring without the experiment. The 500-line budget is a
budget for `Item`, `ItemStore`, `ItemRoutes`, `KeelConfig`, two `main`s and the assembly.

**Consequence — `runUntilSignal` must be called after the server is serving.** The default `watch`
argument installs a signal handler at the moment of the call, so a call placed earlier catches a
signal whose sequence has nothing to drain. This is a property of the default argument and is
invisible at the call site; keel's wiring carries the comment.

**Consequence — the transcript is printed inside `onFinished`, never after the call.** On the JVM
`runUntilSignal` returning means the shutdown hook has returned and the process is already
terminating, so the line after the call does not run. kore's own published example had this defect
and a consumer found it ([kore#59](https://github.com/youndie/kore/issues/59)).

### 1.2 The five platform divergences every native service inherits

kore exists because of these, and they are the pre-filled `quirks` section of
[keel-server](../services/keel-server.md). Each was verified by kore against a dependency artefact,
not against documentation; the addresses below are kore's, re-stated here because a keel reader will
not have kore's research open.

| Divergence | Where kore verified it |
|---|---|
| `EmbeddedServer.stop` runs its steps in **opposite order** on JVM and Kotlin/Native, so `ApplicationStopping` fires *after* the drain on one and *before* it on the other, from identical source | `ktor-server-core-3.6.0!/jvmMain/io/ktor/server/engine/EmbeddedServerJvm.kt`, `ktor-server-core-3.6.0!/posixMain/io/ktor/server/engine/EmbeddedServer.posix.kt` |
| `ApplicationStopPreparing` fires **after** the socket has stopped accepting on CIO, so it cannot be used to flip readiness — which is the one thing its name suggests | `ktor-server-cio-3.6.0!/commonMain/io/ktor/server/cio/CIOApplicationEngine.kt` |
| the Kotlin/Native shutdown hook is **one global slot**, last registration wins, and the callback runs on the POSIX signal-handler stack — `runBlocking` and all | Kotlin/Native 2.4.20 platform klibs; `kore/kore-core/src/nativeMain/kotlin/io/github/youndie/kore/signal/ShutdownSignalWatch.native.kt` is the answer |
| `Connection: close` on a **response** does not close a CIO connection — the engine reads keep-alive from the *request's* header | `ktor-server-cio-3.6.0!/commonMain/io/ktor/server/cio/backend/ServerPipeline.kt` |
| enumerating the environment is `__environ` on Linux and **does not exist** on macOS native, so the unknown-variable check is a declared capability rather than a universal one | `kore/kore-core/src/linuxMain/kotlin/io/github/youndie/kore/config/Environment.linux.kt`, `.../macosMain/.../Environment.macos.kt` |

**Consequence — keel documents them and implements none of them.** They are the argument for taking
kore rather than writing wiring by hand, and a starter whose service document does not name them
lets its first consumer rediscover them one incident at a time.

**Consequence — a sixth divergence belongs beside them and is about exit codes.** A clean `SIGTERM`
shutdown exits `0` on Kotlin/Native and `143` on the JVM; both are correct. keel's parity normaliser
(§1.9, D6) must not compare them, and any smoke test asserts "the process ended itself and was not
`SIGKILL`ed (`137`)".

### 1.3 What `sborka.native-service` enforces, and what it does not

Read in `youndie/sborka` at `fb35de4`.

| Fact | Where verified |
|---|---|
| `nativeService { entryPoint; baseName; allocatorPageSize }`, and `entryPoint` must be set **before** the target is declared or the build fails with "property entryPoint has no value available" | `sborka/build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/native-service.gradle.kts` |
| `binaryOption("fixedBlockPageSize", "16")` is the **default**, not advice; `allocatorPageSize = 0` leaves the compiler's own | same file |
| `stageNativeImage` copies the release `.kexe` to `build/native-image/<baseName>` and writes `<baseName>.needed.txt` beside it, logging what the binary asks the loader for. Never fails: no `readelf`, no answer, reported as an absence | same file |
| `writeNativeDockerfile` writes the reference image **once** and refuses to overwrite an existing `Dockerfile` | same file |
| the size budget is `sborka.binaryBudget` in `gradle.properties`, enforced by **razves**, which the repository applies itself — and the convention fails the configuration if the property is set and razves is absent | same file; `razves` published at `0.1.0.30` on reposilite, read 2026-09-16 |
| the plugin marker resolves as `io.github.youndie.sborka.native-service`, latest `0.4.0.79` | `https://reposilite.kotlin.website/snapshots/io/github/youndie/sborka/native-service/io.github.youndie.sborka.native-service.gradle.plugin/maven-metadata.xml`, read 2026-09-16 |

**Confirmed by keel's first build, 2026-09-16.** `stageNativeImage` wrote
`server/build/native-image/<baseName>` and the `keel.needed.txt` beside it lists **seven** shared libraries —
`libm`, `libpthread`, `librt`, `libdl`, `libgcc_s`, `libc`, `ld-linux-x86-64` — against the ten a
Kotlin/Native binary names by default. `libresolv`, `libutil` and `libcrypt` are gone, which is
exactly what §1.4 says removes the `COPY` line and the builder/runtime glibc pairing rule with it.
The prediction was sborka's; this is the first time it has been read off a binary outside sborka.

**Refuted by the same build: the configuration cache.** The conventions were taken to be usable with
`org.gradle.configuration-cache=true`, which every other repository in this portfolio sets — nothing
said otherwise and nothing had checked. `stageNativeImage` cannot be stored in the cache, so the
build fails with "cannot serialize Gradle script object references". keel is the first build anywhere
to meet it: sborka's stand applies the convention *without* the cache, and katcher, metrik and tracy
run *with* the cache and hand-write their native builds. Filed as
[sborka#76](https://github.com/youndie/sborka/issues/76), and **fixed the same day** —
`sborka 0.4.0.80`, taken in B-14, and the property is back to `true`.

The fix is worth a line because it is larger than the serialisation it was reported for.
`stageNativeImage` now stages from the link tasks' outputs rather than scanning `build/bin`: sborka
found that making the scan lazy was not enough, because a copy spec resolves its sources while the
cache entry is being written, so an entry stored just after a `clean` would report `NO-SOURCE` on
every later run with the binary sitting right there — a task that quietly stages nothing, which is
worse than the failure it replaced. Verified here rather than assumed: with `0.4.0.80` and the cache
on, a clean build stores the entry and stages the binary, a second run reuses the entry, and a third
run with `build/native-image` deleted re-stages it. The whole exchange took one working day, which
is the routing table paying for itself.

**Correction to the brief.** The brief lists `--as-needed` among `sborka.native-service`'s contents.
It is in **`sborka.kmp`**, gated to the Linux target family because `ld64` and `lld-link` reject the
flag — `sborka/build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/kmp.gradle.kts`. The
distinction matters to keel's build file: a module that applies `native-service` and not `kmp` links
without it, gets ten `NEEDED` entries instead of seven, and then needs the `libcrypt.so.1` copy the
convention exists to delete. keel applies both.

**Consequence — explicit API and ktlint are `sborka.kmp` and `sborka.lint`, and keel wants one of
them.** Explicit API is a library's discipline; kore's own sample declines it in as many words,
because a service has no consumers and `public` on every line is noise pretending to be rigour. keel
takes `sborka.lint` and leaves `sborka.explicitApi=false` in `gradle.properties`, with the reason in
the file.

### 1.4 The reference image is `distroless/cc-debian13`, and `base` is not a candidate

| Fact | Where verified |
|---|---|
| the reference Dockerfile is two stages: `gradle:9.7.1-jdk25-noble` building `:<module>:stageNativeImage` under a `~/.konan` cache mount, then `gcr.io/distroless/cc-debian13` carrying the binary and nothing else | `sborka/build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/internal/NativeImageReference.kt` |
| `distroless/base` fails at exec, and not over glibc: Kotlin/Native's exception handling imports **13 `_Unwind_*` symbols** from `libgcc_s`, which `base` does not carry | `sborka/docs/research/research-static-binary.md` §1.2 |
| the image sets `ENV MALLOC_ARENA_MAX=2`, and that number is measured rather than conventional — and **dangerous with `-Xallocator=std`**: same service, peak 39.3 MB uncapped and 413.7 MB capped, 7 of 10 runs surviving instead of 10 | `NativeImageReference.kt`; `sborka/docs/research/research-static-binary.md` |
| a Kotlin/Native binary's `NEEDED` list is the **runtime's**, not the application's: two unrelated services, a CLI and a hello-world give a byte-identical list of ten | `sborka/docs/research/research-static-binary.md` §1.2 |
| the glibc floor is `memcpy@GLIBC_2.14` hard, `__cxa_thread_atexit_impl@GLIBC_2.18` weak — a decade below every base image anyone would consider | same, §1.2 |

**Consequence — keel's `Dockerfile` is sborka's reference with two holes filled, and it is committed
rather than generated.** `writeNativeDockerfile` produces the starting point; the runtime image is a
decision about certificates, shared libraries and a base image's glibc, and it belongs in a file a
person reads and a pull request reviews.

### 1.5 `scratch` needs five paths copied out of the build stage, and the blocker is not the linker

| Fact | Where verified |
|---|---|
| `-static` against glibc does not link; the musl route links only after three archives are shimmed and then **segfaults with no output**, `rc=139` | `sborka/docs/research/research-static-binary.md` §1.6 |
| a statically linked binary still `dlopen`s: Ktor's charset layer on Kotlin/Native **is** glibc `iconv`, and `encodeURLParameter` goes through it — so every page does. A `scratch` image without gconv answers `500` with `Failed to open iconv for charset UTF-8 with error code 22` | same, §1.5c, from a deploy on 2026-09-15 |
| the five paths, taken with `strace -e trace=openat` rather than derived: `/etc/ld.so.cache`, `/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2`, `/lib/x86_64-linux-gnu/libc.so.6`, `/usr/lib/x86_64-linux-gnu/gconv`, `/usr/share/zoneinfo` | same, §1.5c |
| the **whole** gconv directory, not the module that appears in the trace — glibc picked `UTF-16.so` to convert UTF-8. Copying only that one would have saved 2 811 555 bytes | same |
| copied **out of the build stage**, never from the host or another image of the same version: `dlopen` from a static binary needs the same glibc *build* as the `libc.a` it was linked against | same |
| the recipe pins five `konan.properties` keys, and JetBrains' own advice (KT-38876) is that those may change in any patch release — which is why sborka refuses to carry it as an option | same, D3 |
| the upstream ticket is [KT-89362](https://youtrack.jetbrains.com/issue/KT-89362), still open; [JetBrains/kotlin#8127](https://github.com/JetBrains/kotlin/pull/8127) carried the patch and was **closed unmerged on 2026-09-15** | `sborka/docs/research/static-probe/UPSTREAM.md` |

**Correction to the brief — there are five paths, not "four gconv lines", and one of them is not
about gconv.** `/usr/share/zoneinfo` is carried as insurance and nothing reads it:
`TimeZone.currentSystemDefault()` resolves without it, `strace` opens neither it nor `/etc/localtime`,
and both images render UTC. sborka's own research names this as a blocker that dissolved on
measurement, and keeps the 346 KB anyway.

**Consequence — a `STATIC=1` smoke test must reach a rendered page, not a status code.** An earlier
run recorded a `401` from a `scratch` image and read it as a pass; a `401` is produced before any text
crosses a charset. keel's static smoke does `POST /items` and then `GET /items` and asserts the JSON
body, because that is the path `encodeURLParameter` is on.

### 1.6 `sqlx4k-sqlite` publishes a real JVM variant, and it is JDBC underneath

The fact that settles D1, and it is not what the brief assumed. Read from Maven Central on
2026-09-16.

| Fact | Where verified |
|---|---|
| `sqlx4k-sqlite:1.13.1` publishes `jvmApiElements` / `jvmRuntimeElements` alongside `linuxX64`, `linuxArm64`, `macosArm64`, `mingwX64`, `androidNative*`, `ios*` and an `androidJvm` variant | `io.github.smyrgeorge:sqlx4k-sqlite:1.13.1!/sqlx4k-sqlite-1.13.1.module` |
| the JVM variant is a **JDBC facade**: its classes are `SQLite$JdbcConnection`, `SQLite$JdbcTransaction`, and its POM carries `org.xerial:sqlite-jdbc:3.53.4.0` at runtime scope | `io.github.smyrgeorge:sqlx4k-sqlite-jvm:1.13.1!/io/github/smyrgeorge/sqlx4k/sqlite/SQLite$JdbcConnection.class`, `io.github.smyrgeorge:sqlx4k-sqlite-jvm:1.13.1!/sqlx4k-sqlite-jvm-1.13.1.pom` |
| `SQLite.Companion.createConnectionPool` exists on the JVM variant too | same jar |
| the database-agnostic half is the separate coordinate `io.github.smyrgeorge:sqlx4k`, which publishes the same target set; chronik depends on **that** and never on a driver, because two sqlx4k drivers in one Kotlin/Native binary do not link — `duplicate symbol: std::panicking::EMPTY_PANIC` | `chronik/chronik-sqlx4k-sqlite/build.gradle.kts` |

**Consequence — one `ItemStore` implementation, not two.** The brief priced a runnable JVM at two
implementations behind the port and called that a cost rather than a design flaw. It is not a cost
that has to be paid: the same `commonMain` code compiles for both targets against one coordinate,
and the library carries the split — Rust driver on native, Xerial JDBC on the JVM. The port stays,
because the `ktor-server-feature` skill's rule is about testability rather than about targets, and
an in-memory `ItemStore` is what the route's tests run against.

**Consequence — never depend on two sqlx4k drivers.** It does not fail at resolution; it fails at
link, in a message about a Rust symbol. keel takes exactly one, and the comment saying why lives in
the build file rather than only here.

**Settled in B-02, 2026-09-16: one implementation, and it works.** The hypothesis was that the two
variants present an API a single `commonMain` store can be written against — read out of a class
listing and a POM rather than out of a compiled `commonMain`. `SqliteItemStore` is 20 lines of
`commonMain` over `Statement.create(...).bind(...)`, `execute` and `fetchAll`, and its seven-case
contract suite passes against a real database file on `jvm` and on `linuxX64`. The fallback — the
brief's two implementations — was not needed.

Three corrections the build made to this section, each of which was a claim nobody had run:

* **`asString` is a member of `ResultSet.Row.Column`, not an extension.** The `impl.extensions`
  package publishes `asInt`/`asLong`/`asIntOrNull`/`asLongOrNull` and no string decoders, verified by
  `javap` over `sqlx4k-jvm-1.13.1.jar`. Importing it the way the neighbouring repository imports the
  numeric ones fails to compile on every target at once, which is the harmless way to find out.
* **`mode=rwc` is not required on either target.** This document's first draft of the wiring said the
  Rust driver would not create a missing file while Xerial's JDBC would. Removing the parameter and
  running the binary shows `linuxX64` creating the database exactly as the JVM does. The parameter
  stays as a statement of intent — two different drivers, neither documenting the default — and the
  reasoning is now in the code beside it rather than as a fact here.
* **A file, never `:memory:`.** The two halves genuinely do disagree here: the JVM one refuses a pool
  larger than one, because each connection would get a database of its own. A file is also the shape
  the service runs in.

### 1.7 chronik does have a native artefact, on exactly one native target

The fact that settles D2.

| Fact | Where verified |
|---|---|
| `chronik-core` declares `jvm()` and `linuxX64()` and **deliberately no other native target** | `chronik/chronik-core/build.gradle.kts` |
| `chronik-sqlx4k-sqlite` — the store that exists because JDBC "does not travel here" — declares `jvm()` and `linuxX64()` | `chronik/chronik-sqlx4k-sqlite/build.gradle.kts` |
| `chronik-core` is on Maven Central | `https://central.sonatype.com/artifact/io.github.youndie.chronik/chronik-core` |

**Consequence — the timer slot is a commented block with a named hole, not a documented absence.**
The brief's D2 planned for "if JVM-only, the service doc records an absence". chronik is not JVM-only,
so the block is real — and it is `linuxX64` only, which means a keel clone that turns the timer on
**and** builds `linuxArm64` (the property-gated target) fails at resolution with "no matching
variant". That is worth a line in the block and a row in the service document's quirks, because the
failure names an attribute and not the decision that caused it.

### 1.8 zavarnik needs the `application` plugin, which a multiplatform module cannot have

The fact the brief did not have, and the one that costs keel a decision.

| Fact | Where verified |
|---|---|
| zavarnik **refuses** a project without the `application` plugin, with the message "needs the `application` plugin" | `zavarnik/zavarnik-gradle-plugin/src/functionalTest/kotlin/io/github/youndie/zavarnik/ConfigurationChecksFunctionalTest.kt` |
| the cache is trained through the start script over the `lib/*.jar` layout; a classpath of directories — which is what `run` uses — yields no cache; a zip cannot carry it; a wildcard on the start script's classpath is refused at `installDist` | `zavarnik/README.md`, "Requirements" |
| the `application` and Ktor Gradle plugins are `kotlinJvm`-only and do not apply to a multiplatform module; kore's sample assembles its runnable jar by hand for exactly this reason | `kore/samples/service/build.gradle.kts` |
| JDK 25 or newer is required as the toolchain (JEP 514), and the **same JDK build** must run in production as trained | `zavarnik/README.md` |

**Consequence — "one KMP module" and "zavarnik on `check`" cannot both be literal.** See D5. This is
the single place where the brief's contents table and its acceptance list disagree with each other,
and it was found by reading zavarnik's functional test rather than by building anything.

### 1.9 KTOR-9891 is fixed, and it is not the gconv issue

| Fact | Where verified |
|---|---|
| [KTOR-9891](https://youtrack.jetbrains.com/issue/KTOR-9891) is **fixed**: [ktorio/ktor#5874](https://github.com/ktorio/ktor/pull/5874) is merged into the release/3.x branch and targets **3.6.0** | `kotlin-website/site/src/jsMain/resources/markdown/blog/UnderAContainerLimit.md` |
| it is a concurrency finding from the container-limit study, measured on a two-host stand at 2 000 rps — nothing to do with charsets | same |
| the runtime half, [KT-89365](https://youtrack.jetbrains.com/issue/KT-89365), is **open** | same |
| the gconv finding has **no ticket**; it was found at a deploy and lives in sborka's research and in [katcher#55](https://github.com/youndie/katcher/issues/55) | `sborka/docs/research/research-static-binary.md` §1.5c, D3 |

**Correction to the brief.** D3 reads "`distroless/cc` until KTOR's gconv issue lands", which joins
two unrelated things: the Ktor ticket that is fixed and shipping in 3.6.0, and a charset-layer fact
that is not filed anywhere and is not Ktor's bug — glibc has no converters built in. The decision
survives the correction and its address changes; see D3 below.

### 1.10 Versions a clone resolves, as of 2026-09-16

| Fact | Where verified |
|---|---|
| Kotlin `2.4.20`, coroutines `1.11.0`, serialization `1.11.0`, Ktor `3.6.0`, JUnit `6.1.3` are what the portfolio's shared catalog carries | `sborka/catalog/sborka.versions.toml` |
| Kotlin comes from the shared `wip` catalog rather than being pinned here. It was held at 2.4.10 while kore's verification addresses were dumps of that distribution; kore re-ran them against 2.4.20 and every address held | `kore/gradle/libs.versions.toml`, youndie/kore#88 |
| kore compiles its JVM half at toolchain **25**, and a library published at 25 cannot be consumed below 25 | same file |
| `sborka` `0.4.0.79`, `kore-core` `0.1.4`, `razves` `0.1.0.30`, all on `https://reposilite.kotlin.website/snapshots` and none on Maven Central | the three `maven-metadata.xml` listings, read 2026-09-16 |
| `github.com/youndie/keel` does not exist yet | `gh repo view youndie/keel` → "Could not resolve to a Repository", 2026-09-16 |

**Consequence — the JVM floor is 25 and it is decided elsewhere.** zavarnik needs 25 anyway (§1.8),
so the two constraints agree; keel does not get to choose, and the line in its `gradle.properties`
says which repository the number comes from.

### 1.11 `native-service-bootstrap` is already in kotlin-skills

| Fact | Where verified |
|---|---|
| the skill is the tenth of ten in the plugin, with `references/{memory-under-a-limit,scratch-image,build-time,sqlite-under-load}.md` and `examples/deploy.md` | `kotlin-skills/plugins/kotlin-fullstack/skills/native-service-bootstrap/` |
| it already names the split keel depends on: "**This file describes and measures; the convention compels**", mechanisms having moved to sborka and kore on 2026-09-15 | `.../native-service-bootstrap/SKILL.md` |
| it tells the agent to copy a **living service** — metrik or katcher — rather than the templates in the file | same, Step 0 |
| there is **no `evals/evals.json`** anywhere in the plugin | `kotlin-skills/plugins/kotlin-fullstack/` holds `skills/` and nothing else |

**Consequence — one deliverable of the brief is already done and one is not.** The move into
kotlin-skills happened; what is missing is keel as the skill's reference project (Step 0 names two
production services, and a starter is a better answer for "a new service") and the eval suite. Both
are backlog items here rather than assumptions: [B-11](../backlog/B-11-skill-points-at-keel.md),
[B-12](../backlog/B-12-skill-evals.md).

---

## 2. Decisions

### D1. One `ItemStore` implementation on both targets — *deviation from the brief*

Brief: sqlx4k on both if its JVM artefact is real, otherwise sqlx4k on native and Exposed/JDBC on the
JVM behind the port; "two implementations are the price of a runnable JVM, not a design flaw".

Decision: **one implementation in `commonMain` over `sqlx4k-sqlite`**, and the port stays for tests
rather than for targets.

Why:

- the JVM artefact is real and is itself JDBC — §1.6. Writing an Exposed implementation beside it
  would be re-implementing, in keel, the split the library already publishes;
- the price the brief was willing to pay was two implementations *and* the drift between them, which
  is the thing a starter must not teach: the first consumer copies whatever keel does;
- what the port buys is unchanged — an in-memory `ItemStore` is what `ItemRoutes`' tests run against,
  which is the `ktor-server-feature` rule's actual content;
- the cost, honestly: if the common surface does not typecheck against both variants the fallback is
  the brief's original two implementations, at the price of one build finding out —
  [B-02](../backlog/B-02-one-store-on-both-targets.md).

### D2. The chronik timer block is real, and it names its target hole

Brief: settled by reading chronik's targets; if JVM-only, a documented absence.

Decision: **a commented block that works**, with the line "`linuxX64` only — turning this on with
`keel.linuxArm64=true` fails at resolution" beside it.

Why: §1.7. An absence would have been information; a working block with a named hole is more, and the
hole is exactly the kind that reports itself as "no matching variant" and sends the reader to the
wrong file.

### D3. `distroless/cc-debian13` by default, `scratch` behind `--build-arg STATIC=1` — *the reason changed, the decision did not*

Brief: "`distroless/cc` until KTOR's gconv issue lands; settled by the ticket, not by preference".

Decision: unchanged in effect, and the ticket it waits on is **not** the one the brief named.

Why:

- KTOR-9891 is fixed and ships in Ktor 3.6.0, and it is about concurrency under load, not charsets —
  §1.9. Waiting on it would have been waiting on something that has already happened, for a reason
  that was never true;
- what actually holds `scratch` back is two things with different owners: [KT-89362](https://youtrack.jetbrains.com/issue/KT-89362)
  (`-static` is undone twice — open, its patch closed unmerged on 2026-09-15), and the charset layer's
  `dlopen`, which is not a bug in anything and is answered by copying five paths out of the build
  stage;
- so the default stays `distroless/cc-debian13`. **`STATIC=1` is not a flag, and that is a deviation
  from the brief taken in B-16 rather than quietly** — see §2 D8 below;
- the price: the measured one is 4.5 MB on an image that came in at 13 972 497 bytes against a 25 MB
  budget, and both numbers are in the README rather than left for a reader to discover by trying.

### D4. `:server:measure` refuses to write `docs/research/` without `--stand`

Brief: unchanged, and adopted as written.

Why: a local number is a number about a laptop, and the last post shipped a wrong table that way.
A local run prints and exits; a stand run naming two hosts writes `docs/research/measurements-<date>/`.
The two-host shape is what the container-limit study used (§1.9), so "the stand" is a thing that
exists rather than an aspiration.

### D5. The JVM half is a second module, and `:server` stays multiplatform — *new, and it contradicts the brief's contents table*

Brief: one KMP module, `installDist` and `linkReleaseExecutable*` from it, zavarnik's `aotVerify` on
`check`.

Decision: **`:server` is the KMP module (`jvm()` + `linuxX64`, `linuxArm64` behind a property), and
`:distribution` is a tiny `kotlin("jvm")` module that applies `application` and zavarnik and depends on
`:server`'s JVM target.** The Kotlin in it is one `main` calling into `:server`.

Why:

- zavarnik refuses a project without `application`, and `application` does not apply to a
  multiplatform module — §1.8. The three ways out are: drop zavarnik (loses an acceptance criterion
  and the whole "the JVM half is shipped, not only tested" point), hand-assemble a fat jar as kore's
  sample does (loses `installDist`, which is precisely what zavarnik trains through), or split;
- the split is the only one that keeps all three of `installDist`, a trained cache and one copy of the
  code. The alternative that looks cheapest — a fat jar — is the one zavarnik's requirements rule out
  by name: "a classpath of directories yields no cache", and a single jar is not the `lib/*.jar` layout
  it trains over;
- the price is one extra module and roughly ten lines of Gradle against acceptance 6's budget of 100.
  It is named here rather than discovered, and if the budget is what breaks, the honest answer is
  dropping zavarnik from the template — not hiding the module;
- what this does **not** do is split the code. `:distribution` has no Kotlin beyond `main`; a starter
  whose logic lives in a JVM-only module has quietly stopped shipping twice.

**Settled in B-03, and it cost more than the module.** `installDist` produces a start script that
runs, `aotVerify` reports **2355 of 2355 application classes (100 %) from the cache**, and the
distribution answers `/health/ready` and serves `GET`/`POST /items`. Four things had to be decided or
found on the way, none of them visible from this decision as written:

* **The budget had to be redefined before the module could exist at all** — 115 code lines against
  100, counting the version catalog. It now counts build logic only, which is 87 with the module in.
  The reasoning, and the fact that a criterion was edited by the work it constrained, is in
  `backlog.md`.
* **The module is `:distribution`, not `:server-jvm`.** Kotlin names a multiplatform module's JVM
  artefact `<module>-jvm-<version>.jar`, so `:server`'s is already `server-jvm-0.1.0.jar`; a module of
  that name produces a duplicate in `lib/` and `installDist` refuses.
* **Two sibling modules applying different Kotlin plugins need a root build script with
  `apply false`**, or each plugin lands in its own classloader scope and the Kotlin plugin's shared
  `KotlinNativeBundleBuildService` exists twice. The failure names two classloaders and nothing about
  the cause. keel had no root build file until this item; every clone that adds a second module meets
  it.
* **`KEEL_DB_PATH` stopped being required**, for two reasons of which **only one still holds**.
  zavarnik's training run could not be given an environment
  ([zavarnik#13](https://github.com/youndie/zavarnik/issues/13)) — fixed in `0.1.0.41`, and B-20 took
  it, so the training run now has a database path of its own. What stands is the other: the README
  promises `./gradlew run` works on a fresh clone, which a required key makes false. keel declares
  **no** required key, which is a fact about a template whose store is a file beside the process
  rather than a lesson, and `KeelConfigTest` keeps the required shape in a test so it is not lost with
  the key.

Twelve of the module's lines are not keel's: [B-17](../backlog/B-17-adopt-the-jvm-distribution-convention.md)
adopts them from sborka once [sborka#78](https://github.com/youndie/sborka/issues/78) exists.

### D6. Parity is asserted against a declared normaliser, and the normaliser is written before the first run

Brief: acceptance 4, "no diff after the declared normaliser".

Decision: the normaliser is a file in the repository — [B-05](../backlog/B-05-parity-smoke.md) — and
it declares, before any run, what is allowed to differ: the exit code (`0` native, `143` JVM, §1.2),
the `Server` header, `Date`, and the `/version` body's build time. Everything else is a diff and
fails.

Why: a normaliser written after the first red run is a list of whatever differed, and it will absorb
the next real divergence without anybody noticing. sborka's own parity convention exists for the same
reason and asks the platform through Ktor rather than through the syscall underneath, because the
failure this portfolio paid for was in Ktor's `InetSocketAddress` while every syscall below it worked
(`sborka/docs/research/research-parity.md` §1.5).

### D8. keel documents the `scratch` recipe and does not ship it — *deviation from the brief*

Brief: *"`scratch` behind `--build-arg STATIC=1` with the four gconv lines and the curl caveat in a
comment"*.

Decision, taken in [B-16](../backlog/B-16-static-image.md): the recipe is written down — §1.5 above
has the five paths, the build-stage rule and the rendered-page acceptance — and the `Dockerfile`
carries no static variant.

Why:

- **sborka refused the same recipe as a convention option, and its reason applies here with more
  force.** The recipe pins five `konan.properties` keys, and JetBrains' advice on that mechanism
  (KT-38876) is that they may change in any patch release. sborka's D3: *"An option in a shared
  convention plugin that breaks on a Kotlin bump, silently, in someone else's service, costs more
  than the 9 MB it saves."*
- **A template is that hazard with a longer fuse.** A convention is fixed once and every consumer
  picks the fix up; a template is copied and never updated again. Every clone would carry a build
  that breaks on a Kotlin bump, in a repository whose owner has never read this document, and §1.6
  records what that failure looks like: four link attempts each failing differently, ending in a
  segfault with no output.
- **The prize is smaller than it was when the brief was written.** ~4.5 MB against an image already
  44 % under its budget. The brief declared 12 MB for the static image without knowing the dynamic one
  would come in at 14.
- The price, honestly: a clone that wants `scratch` follows a recipe instead of passing a flag, and
  `scratch`'s other benefit — no shell to `kubectl exec` into — is not on offer by default.

**Not settled by a ticket, and no longer waiting on one.** This decision first carried
[KT-89362](https://youtrack.jetbrains.com/issue/KT-89362) as its expiry, on the reasoning that a fixed
`-static` would make the recipe two lines and reopen the question. The owner closed that on
2026-09-16: keel ships on `distroless/cc-debian13`, full stop. The prize was ~4.5 MB on an image
already 44 % under its budget, so the trade was never close enough for a ticket to swing it, and an
open item implied otherwise. [B-18](../backlog/B-18-scratch-when-static-is-static.md) is `dropped`.

§1.5 stays, because it records facts about Kotlin/Native and glibc rather than about this decision —
anybody who wants `scratch` follows them deliberately.

### D7. The documentation tree carries drafts on `main`, with the gate off and addressed

Decision: every document outside `research/` is `status: draft` until the code exists, and
`docs_check.py --on-main` is **off**, with [B-10](../backlog/B-10-draft-gate.md) as its address.

Why: the invariant is that `main` describes what exists. A docs-first template repository has nothing
that exists yet, and the two honest ways to hold the invariant are to keep the whole tree in an open
pull request until the code lands, or to say in one place that the tree is intent and name the item
that turns the gate on. kore took the second (its own `B-35`) and it worked: the flag is a line in CI,
not a relaxed rule. What is not acceptable is a tree of `active` documents describing code nobody has
written.

---

## 3. Risks and open questions

**Risk 1 — keel accumulates fixes and turns back into konekt.** Mitigation: the defect routing table
in [the service document](../services/keel-server.md) §3 and in `CLAUDE.md` — a build flag goes to
sborka, lifecycle goes to kore, a procedure goes to the skill, and nothing goes to keel except
renaming. The mechanical half is acceptance 6's two line budgets, which fail loudly when something
that belongs elsewhere is added here.

**Risk 2 — a check that passes on one target and is skipped on the other.** This is in the brief's
own red list, and it is not hypothetical: in kore, a green `build` on one host meant `linuxArm64Test`
**did not exist** (Kotlin/Native has no `linux_arm64` host, so the plugin never creates the task) while
`macosArm64Test` was *disabled* — one word, "SKIPPED", over two different states. Mitigation: keel's
CI prints every test result file with its counts and fails when there are none, because a suite that
ran zero tests exits zero. [B-06](../backlog/B-06-both-targets-tested.md).

**Risk 3 — the template's first consumer is also its only test, and it is run by the person who wrote
it.** Mitigation: the defect count is kept by a human who is not the agent doing the work, and every
line the agent had to add that is neither domain nor renaming is filed against sborka, kore or the
skill — the brief's table, adopted verbatim. The number that matters is "lines added that were not
domain and not renaming", and it is recorded in [B-09](../backlog/B-09-first-consumer.md) whatever it
turns out to be.

**Risk 4 — `MALLOC_ARENA_MAX=2` travels into a service that changes allocator and is silently
worse.** It is in the image keel ships, it is measured on a different service, and the pairing with
`-Xallocator=std` cost 7 survivals out of 10 (§1.4). Mitigation: the line in keel's `Dockerfile`
carries the counter-example and the instruction to re-measure, not just the value.

**Open question 1 — does the whole of acceptance 1 fit in an hour of wall time on a cold machine?**
The toolchain fetch alone is ~1 GB of Kotlin/Native, the builder image is `gradle:9.7.1-jdk25-noble`,
and nothing here has been timed. Hypothesis: yes, dominated by the two downloads, and the Gradle
`~/.konan` cache mount keeps the second image build off the network. Settled by
[B-08](../backlog/B-08-clone-to-ready.md), which records the measured number in the README with the
date it was taken — and if it is two hours, the README says two hours.

**Open question 2 — is 25 MB (`distroless/cc`) / 12 MB (`STATIC=1`) the right budget?** The brief
declares both before the first commit, which is the right order; the numbers next to them are from
neighbouring services rather than from keel. **One half is now measured, twice**: B-01's `linuxX64` release binary was **4 983 240 bytes**, and
B-02's — the same binary with the SQLite driver and its Rust runtime linked in — is **9 227 448**.
The driver costs 4.2 MB, which is the single largest thing keel will ever add to itself, and it is
still well under tracy's 13 863 696. The image is unmeasured and the binary is only its payload, so
B-04's 25 MB is not yet in any danger from this direction — but the margin is now 15 MB rather than
20, and a clone that adds a TLS client spends more of it. tracy's `:server` binary is 13 863 696 bytes unstripped
and 10 241 264 stripped, and the static probe's image was 9 570 311 bytes to pull *with the whole
gconv directory*. Hypothesis: the `distroless/cc` budget is comfortable and the static one is tight
by about the size of gconv. Settled by [B-04](../backlog/B-04-image-and-size-budget.md); a budget
that is missed gets a new number **and a line saying what it bought**, not a quiet edit.

**Open question 3 — what does the "stranger test" cost, and when?** The brief defers it: a clone with
no portfolio repository configured needs `kore-core`, `kore-ktor` and sborka on Maven Central, which
is a month out (§1.10 — none of the three is there). Until then the README carries the reposilite
block with the date it is expected to go. Not an item here, because it is not keel's work.

---

## 4. What happens next

The order is in [backlog.md](../../backlog.md). The first three items are the ones everything else
rests on, and they are ordered by what can refute the design earliest:

1. **[B-01](../backlog/B-01-repository-skeleton.md)** — the repository builds both targets with the
   conventions applied and nothing in its own build files that sborka could carry. The moment a flag
   has to be added here, the brief's red list has been hit and the finding goes to sborka.
2. **[B-02](../backlog/B-02-one-store-on-both-targets.md)** — D1's hypothesis, settled by one build.
   If it fails, the port takes two implementations and §1.6 is amended at the point of divergence.
3. **[B-03](../backlog/B-03-jvm-half-ships.md)** — D5's module split, `installDist` with a verified
   AOT cache. This is where acceptance 6's Gradle budget is first under real pressure.
