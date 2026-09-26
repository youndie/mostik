---
id: B-02
title: "One ItemStore implementation compiles and passes its contract suite on both targets"
status: done
priority: P0
size: M
stage: m1-ships-twice
epic: feature-item-round-trip
blocked_by: [B-01]
---

# B-02 — One store, both targets, or the fallback

[research-architecture](../research/research-architecture.md) D1 decided that keel writes **one**
`ItemStore` over `sqlx4k-sqlite` rather than the two implementations the brief priced in, because the
library already publishes the split: the Rust driver on native and `org.xerial:sqlite-jdbc` on the
JVM. That was read out of the `.module` metadata and the JVM jar's class listing, not out of a
compiled `commonMain` — §1.6 says so and calls it a hypothesis. This item settles it.

- **The decision and its reason.** If the common surface typechecks against both variants, keel keeps
  one implementation and the port exists only so the route's tests can run against an in-memory
  store. If it does not, the fallback is the brief's original shape — sqlx4k on native, JDBC on the
  JVM behind the same port — and §1.6 is amended **at the point of divergence**, keeping the reason
  the first reading was wrong.
- The alternative of writing two implementations up front is worse for a starter specifically: a
  clone copies whatever keel does, and two implementations that drift is the defect the template
  would be teaching.
- **Exactly one driver.** Two sqlx4k drivers in one Kotlin/Native binary do not link —
  `duplicate symbol: std::panicking::EMPTY_PANIC` — and it is a link error, not a resolution error.
  The build file carries that comment whichever way this item goes.
- Not covered: migrations beyond the single `CREATE TABLE`, and connection-pool tuning.

- AC: `jvmTest` and `linuxX64Test` both run `ItemStoreContractTest` against a real SQLite file and
  **both report a non-zero test count**, read from the result file rather than from `BUILD
  SUCCESSFUL`.
- AC: whichever way it goes, research §1.6 says so in writing, with the compiler's message if it
  failed.
- Anchors: `server/src/commonMain/kotlin/.../item/ItemStore.kt`,
  `server/src/commonTest/kotlin/.../item/ItemStoreContractTest.kt`,
  `chronik/chronik-sqlx4k-sqlite/build.gradle.kts`

---

## Iteration 1 — 2026-09-16, done

**D1 is settled: one implementation.** `SqliteItemStore` is twenty lines of `commonMain` over
`Statement.create(...).bind(...)`, `execute` and `fetchAll`, and its contract suite passes against a
real database file on **both** targets. The fallback the item reserved — the brief's two
implementations — was not needed and research §1.6 says so at the point the hypothesis was written.

| AC | Evidence |
|---|---|
| `jvmTest` and `linuxX64Test` both run the contract suite against a real SQLite file | 7 cases each; result files read, not `BUILD SUCCESSFUL` |
| both report a non-zero count | 22 tests per target in total, printed by CI's named-target report |
| whichever way it goes, research §1.6 says so in writing | settled, with the three corrections below |

### The defect this item shipped, and how it was caught

**The service persisted nothing, and every test passed.** A broken string template meant the URL
named the expression instead of the path: `POST` answered `201`, `GET` returned what had just been
written, and a restart came back empty with no file ever created on disk.

No test could have caught it. The store suite builds its own URL, so it exercised the store against a
database it had opened correctly — writing and reading the same wrong way, which is the failure mode
that suite was warned about in the skill and still walked into. **Only running the binary twice found
it.**

Two things now stand where that gap was:

* `keelDatabaseUrl(path)` is a named function in `commonMain` and `KeelDatabaseUrlTest` asserts, among
  other things, that the URL contains no uninterpolated `$`. The suite opens its databases through
  that same function, so the service's URL builder is on the tested path rather than beside it.
* `the rows survive the driver being closed and reopened` is the one case in the contract suite that
  a process-local database fails. Every other case passes against one.

### Three corrections, each a claim nobody had run

* **`asString` is a member of `ResultSet.Row.Column`, not an extension** — `impl.extensions` publishes
  the numeric decoders only, verified with `javap` over `sqlx4k-jvm-1.13.1.jar`. Importing it the way
  the neighbouring repository imports `asInt` fails to compile everywhere at once, which is the
  harmless way to find out.
* **`mode=rwc` is not required on either target.** The comment beside it first said the Rust driver
  would not create a missing file. Removing the parameter and running the binary shows `linuxX64`
  creating the database exactly as the JVM does. It stays as a statement of intent — two different
  drivers, neither documenting the default — and the code now says that instead of the false thing.
* **A duplicate id is refused, and the message differs between the two drivers**, so the suite asserts
  that it fails rather than what it says. Pinning the text would be writing one platform's phrasing
  down as the contract.

### Mutation

| Mutation | What died |
|---|---|
| drop `?mode=rwc` from `keelDatabaseUrl` | the two URL tests — and, informatively, **nothing else**, which is what established the correction above |

### One finding routed out of keel

**[razves#4](https://github.com/youndie/razves/issues/4)** — `binarySize.budget` applies to every
executable, and the debug binary is 28,580,560 against the release one's 9,227,448. One number cannot
watch both: a ceiling debug fits under is one the shipped binary could triple beneath unnoticed. keel
disables the debug check and keeps the real 25 MiB on release, in the only line of its build files
that is not "apply a convention and set a name" — quirk 16, with the issue named.

### The binary grew, and it is the largest thing keel will add to itself

4 983 240 → **9 227 448** bytes: the SQLite driver's Rust runtime costs 4.2 MB. Still well under
tracy's 13 863 696, and B-04's image budget now has 15 MB of margin rather than 20.
