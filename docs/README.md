# docs — keel

keel is a template repository for a Kotlin server that ships twice: one source, a JVM distribution
and a Kotlin/Native binary, both runnable, both tested, one image, every check green on day one. It
is konekt with the domain removed. The documentation is layered; links run top to bottom.

```
[ Research — why the template is shaped this way; verified vs hypothesis ]
                              │
[ Feature — what a clone gets, + BDD scenarios = the template's acceptance ]
                              │
[ API — every route keel serves, keel's own and kore's ]
                              │
[ Service — the modules, how they are built, and twenty quirks ]
```

There is **no `screens/` layer** and there will not be one: keel ships no client, by the brief's
first non-goal. A client template is a different repository.

| Layer | Directory | Answers | Source of truth |
|---|---|---|---|
| Research | `research/` | *why* it is built this way; what was verified and against what | the artefacts and repositories each fact names |
| Feature | `features/` | *what* a clone gets, and the scenarios that are the template's acceptance | this repository |
| API | `api/` | every route, its status codes, and what is deliberately not promised | the contract class named in the document |
| Service | `services/` | the modules, how they are built, the quirks inherited from the platform | this repository |

**Backlog** — [backlog.md](../backlog.md): the goal, the stages and the index; the items themselves
are one file each in [`backlog/`](backlog/), cited as
[B-01](backlog/B-01-repository-skeleton.md).

## Read this first

**Every document here is `active`, and each was re-read against the code rather than flipped** —
[B-10](backlog/B-10-draft-gate.md), which records the four sentences that re-reading corrected. One of
them was not a stale reference but an instruction that ran and silently produced the wrong artefact,
which is why the re-reading is the point and the status field is only its result.
`docs_check.py --on-main` enforces it on the default branch.

**`active` does not mean everything described is built; it means nothing described is wrong.** What is
absent says so where a reader meets it: a `scratch` image (B-16, by decision), `linuxArm64` coverage
(B-15), an automated stand run (B-13 — that measurement was taken by hand).

This section said the opposite for most of the tree's life, and correctly: until B-10 every layer
document was `draft` and the gate was off with B-10 as its address. The history is in the item rather
than quoted here.

What *is* verified is [research-architecture](research/research-architecture.md) §1: eleven groups of
facts, each read on 2026-09-16 in a published artefact (`sqlx4k-sqlite:1.13.1`'s Gradle metadata and
JVM jar, Ktor 3.6.0's sources, the Kotlin/Native 2.4.20 platform klibs), in a portfolio repository's
source (kore, sborka, zavarnik, chronik), or in a registry listing. Two of the brief's four open
decisions came out differently from what it assumed, and one decision is new and contradicts the
brief's own contents table — §2, D1, D3 and D5.

The brief as it arrived is kept verbatim at
[source-brief-keel](research/source-brief-keel.md), so the deviations stay readable as deviations.

The distinction between those two paragraphs is the point of this whole tree. A document that cannot
establish something says it does not cover it; a document that blurs a plan into an observation is a
defect worth a backlog item, because both halves then look equally authoritative.

## Conventions

- **`id`** in the frontmatter is unique and equals the filename.
- Cross-layer links are ids in the frontmatter and ordinary markdown links in the body.
- One document, one entity. `client_entries: []` on the feature is the answer "keel has no client",
  not a field somebody forgot.
- BDD scenarios are written from behaviour, not from intent. While the code does not exist they are
  **target** behaviour and carry no `**Automated:**` line — the absence is the honest signal, and
  `bdd_report.py` counts every one of them as manual.
- **The primary consumer is a coding agent.** Every document carries code anchors. Addresses inside a
  dependency's artefact are written with the separator a jar URL uses —
  `ktor-server-core-3.6.0!/posixMain/io/ktor/server/engine/EmbeddedServer.posix.kt` — because no search
  over sibling repositories can ever resolve them, and a permanently non-zero rot list is a list
  nobody reads.
- `keel-server.md` carries `repo_url` since B-01, and not before: the repository did not exist when
  the tree was written, and a URL written before its repository is intent documented as fact. The
  checker's warning about its absence was the correct state for exactly one commit.
- Do not duplicate what lives in code: give the path. A copy rots, a path does not.
- **Language: English**, documents and code alike. HTTP headers, environment variable names and
  identifiers verbatim.

## Templates

`templates/` holds a copy of the document templates, so the format travels with the repository.
Sections marked `<!-- optional -->` can be deleted.

## Checks

```bash
pip install pyyaml
make check
```

`make check` is the gate and CI runs exactly it. `make report` is the two non-blocking reports:
`bdd_report` counts scenarios, which is meaningless as a percentage while every scenario is target
behaviour, and `code_anchors` cannot tell a path quoted as obsolete from a live one.

## Coverage map

The list below is **checked** against the files on disk: a document missing here, or an entry with no
file behind it, fails `coverage_map.py`. The grouping and the descriptions are written by a person —
the machine only guards the membership.

### Research (3)

- [x] [research-architecture](research/research-architecture.md) — what was read in the artefacts and the repositories, what follows from it, and the seven decisions including the three that deviate from the brief
- [x] [source-brief-keel](research/source-brief-keel.md) — the brief as it arrived, kept verbatim so the deviations stay readable
- [x] [measurements-2026-09-16](research/measurements-2026-09-16.md) — the first stand measurement: time to ready, RSS, p95, with the raw k6 output in the directory beside it

### Services (1)

- [x] [keel-server](services/keel-server.md) — the template service: two modules, how it is built, where a defect goes, and twenty quirks

### Features (1)

- [x] [feature-item-round-trip](features/feature-item-round-trip.md) — `POST /items` then `GET /items` on both targets; the scenarios are the template's acceptance criteria

### API (1)

- [x] [endpoint-items](api/endpoint-items.md) — every route keel serves, keel's two and kore's five, with the `/health` alias warning
