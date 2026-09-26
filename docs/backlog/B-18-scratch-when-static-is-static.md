---
id: B-18
title: "Ship the scratch image once -static needs no property overrides"
status: dropped
priority: P3
size: S
stage: m2-image
epic: feature-item-round-trip
---

# B-18 — The address B-16's decision was settled against

[B-16](B-16-static-image.md) decided keel documents the `scratch` recipe and does not ship it, because
it pins five `konan.properties` keys JetBrains may change in any patch release and a template is
copied and never updated again. That decision has an expiry, and this is it.

- **The trigger is [KT-89362](https://youtrack.jetbrains.com/issue/KT-89362)** — "`-static` is undone
  twice", filed by sborka on 2026-09-13, still open; [JetBrains/kotlin#8127](https://github.com/JetBrains/kotlin/pull/8127)
  carried the patch and was closed unmerged on 2026-09-15. When `-static` produces a static binary
  with no overrides, the recipe stops being five pinned properties and becomes a linker option.
- **What does not change when it lands.** The gconv copies still have to happen: glibc has no
  converters built in, Ktor's charset layer on Kotlin/Native *is* glibc `iconv`, and a statically
  linked binary can still `dlopen`. Five paths, copied **out of the build stage**, and a smoke test
  that reaches a rendered page rather than a status code — research §1.5. That half is not blocked on
  anything and is not the reason B-16 said no.
- The rejected alternative is watching the ticket by hand. An item with the ticket in it is read when
  somebody opens the backlog; a good intention is not.

- AC: `--build-arg STATIC=1` builds an image that serves `GET`/`POST /items` with a **rendered body**,
  and its size is recorded in the README beside the dynamic one.
- AC: research §2 D8 is amended at the point of divergence rather than deleted — the reasoning for
  not shipping it stays readable next to the reason it stopped applying.
- Anchors: `Dockerfile`, `docs/research/research-architecture.md`, `README.md`

---

## Dropped 2026-09-16

**keel ships on `gcr.io/distroless/cc-debian13`, and that is the end of the question rather than a
position held until a ticket lands.** Decided by the owner.

What changes is not the image — B-16 already chose it — but what this item was: an *expiry*. B-16's
reasoning was "not now, because the recipe pins five `konan.properties` keys", which carries the
implication that a fixed `-static` would reopen it. It would not. The `scratch` image was worth
**~4.5 MB on an image that came in 44 % under its budget**, and that was the whole prize; the rest of
what `scratch` offers — no shell to `kubectl exec` into — is a thing keel has no opinion about.

So the trade was never close, and keeping an item open against a JetBrains ticket kept implying it
was. [KT-89362](https://youtrack.jetbrains.com/issue/KT-89362) is no longer keel's business.

**What survives is the research**, and it should: §1.5 records the five paths, the build-stage rule
and the rendered-page acceptance, because those are facts about Kotlin/Native and glibc rather than
about this decision. Anybody who wants `scratch` follows them deliberately — which is exactly what
B-16 said the alternative to shipping it was.
