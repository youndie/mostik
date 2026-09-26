# docs — mostik

mostik is an HTTP → Kafka bridge whose status code is a true statement about the record. It is built
from [keel](https://github.com/youndie/keel) and publishes through
[kafkakn](https://github.com/youndie/kafkakn). The documentation is layered, and links run top to
bottom.

```
[ Research — why the answers are shaped this way; verified vs hypothesis ]
                              │
[ Feature — publishing over HTTP; shutting down without loss; BDD = acceptance ]
                              │
[ API — every route, its status codes, what is not promised ]
                              │
[ Service — the modules, how they are built, the quirks ]
```

There is **no `screens/` layer**: mostik has no client.

| Layer | Directory | Answers | Source of truth |
|---|---|---|---|
| Research | `research/` | *why* it is built this way; what was verified and against what | the artefacts and repositories each fact names |
| Feature | `features/` | *what* a caller gets, and the scenarios that are the acceptance | this repository |
| API | `api/` | every route, its status codes, and what is deliberately not promised | the route code |
| Service | `services/` | the modules, how they are built, the quirks | this repository |

**Backlog**: [backlog.md](../backlog.md) holds the goal, the stages and the index. The items are one
file each in [`backlog/`](backlog/), cited as [B-01](backlog/B-01-from-template-to-a-bridge-that-links.md).

## Read this first

**Every document here is `active`, and each was re-read against the code in B-03, not flipped.**
`active` does not mean everything described is built. It means nothing described is wrong: what is not
built yet is marked *target* where a reader meets it, with the item that builds it. The deadline (B-05) and
the shutdown checks (B-07, B-08, B-09) are the largest of those. `docs_check.py --on-main` refuses a `draft`
on the default branch.

**What is verified** is [research-architecture](research/research-architecture.md) §1: each fact
carries an address inside kafkakn, keel, kore or a registry listing, with the date it was read. The
brief turned out wrong in two places, and both are recorded where they matter:

- its premise that kafkakn cannot bound `send` (§1.1);
- the toolchain move it planned (§1.7).

## Conventions

- **`id`** in the frontmatter is unique and equals the filename.
- Cross-layer links are ids in the frontmatter and ordinary markdown links in the body. A backlog item
  carries its feature as `epic:`, which the checker holds to a document that exists. That is why the
  field arrives on `main` with the feature documents, and not before.
- BDD scenarios are **target** behaviour until the code exists, and carry no `**Automated:**` line.
- Addresses inside another repository or artefact use the separator a jar URL uses:
  `youndie/kafkakn@2f209b0!/docs/api/producer-contract.md`.
- Do not duplicate what lives in code: give the path.
- **Language: English**, documents and code alike (research D9 — open until the owner confirms).

## Checks

```bash
pip install pyyaml
LOCAL=1 make check
```

`make check` is the gate and CI runs exactly it. `make report` runs the two non-blocking reports.

## Coverage map

The list below is **checked** against the files on disk. The grouping and the descriptions are written
by a person; the machine only guards the membership.

### Research (1)

- [x] [research-architecture](research/research-architecture.md) — what kafkakn's `send` does when cancelled, what keel's shutdown already does, the two places the brief was wrong, and the decisions behind the status set

### Services (1)

- [x] [mostik-server](services/mostik-server.md) — the bridge in two builds: where the deadline lives, the shutdown order with the producer last, the `MOSTIK_` and `KAFKA_` configuration, and three quirks

### Features (2)

- [x] [feature-publish-over-http](features/feature-publish-over-http.md) — one `POST`, one record, a status that is true about the record; six target scenarios decided by reading the topic
- [x] [feature-shutdown-without-loss](features/feature-shutdown-without-loss.md) — `SIGTERM` without making any answer false, and why the drain must outlast the deadline

### API (2)

- [x] [endpoint-records](api/endpoint-records.md) — `POST /topics/{topic}/records`: how the request becomes a record, the closed status set, and kore's `503`
- [x] [endpoint-probes](api/endpoint-probes.md) — kore's probes and `/version` as keel mounts them; readiness does not follow the broker
