---
id: B-16
title: "Should the template ship the scratch recipe at all?"
status: done
priority: P2
size: M
stage: m2-image
epic: feature-item-round-trip
---

# B-16 — The `STATIC=1` variant, and whether a template should carry it

B-04 built and measured the default image. The brief also asks for `scratch` behind
`--build-arg STATIC=1`, and that half is not a task — it is a decision, because sborka already took
the analogous one the other way and its reasoning applies here with more force rather than less.

## What the recipe is

From [sborka's static-binary research](https://github.com/youndie/sborka) §1.5c and D3: five
`konan.properties` overrides, `g++` in the build stage (the `gradle:9.7.1-jdk25-noble` image ships no
static archives), and five paths copied **out of the build stage** — `/etc/ld.so.cache`, the loader,
`libc.so.6`, the whole `gconv` directory and `/usr/share/zoneinfo`. The gconv directory is not
optional: Ktor's charset layer on Kotlin/Native *is* glibc `iconv`, reached through `dlopen`, so
without it every rendered page is a `500` with `Failed to open iconv for charset UTF-8 with error
code 22`.

## Why it is a question

**sborka refused to carry this as an option, and said why:** the recipe pins five `konan.properties`
keys, and JetBrains' own advice on that mechanism (KT-38876) is that they may change in any patch
release. *"An option in a shared convention plugin that breaks on a Kotlin bump, silently, in someone
else's service, costs more than the 9 MB it saves."*

A template is the same hazard with a longer fuse. A convention plugin can be fixed once and every
consumer picks it up; **a template is copied and never updated again**. Every clone would carry a
build that breaks on a Kotlin bump, in a repository whose owner has never read this item, and the
failure arrives as a link error with no diagnostic — §1.6 of that research is four link attempts each
failing differently, ending in a segfault with no output.

Against that: the measured prize is real. The static image is roughly 9.5 MB against this one's 14.0,
and `scratch` has no shell to `kubectl exec` into, which some people want.

## The options

| | |
|---|---|
| **1. Ship it behind `STATIC=1`**, as the brief asks, with the caveats in comments | the brief's letter; every clone inherits a recipe with a known expiry |
| **2. Do not ship it; document it** — a `docs/` section naming the recipe, the ticket ([KT-89362](https://youtrack.jetbrains.com/issue/KT-89362), open, its patch closed unmerged) and the 4.5 MB it would save | a clone that wants it can follow the recipe deliberately; nobody inherits it by accident |
| **3. Wait for KT-89362** and ship it when `-static` means static without overrides | the recipe is then two lines and this whole item dissolves |

**A recommendation, since one is owed: 2, then 3.** The saving is 4.5 MB on an image that is already
44 % under its budget, and the cost is a build that breaks silently in repositories nobody is
watching. That is the same trade sborka made, and keel is further from the fix than sborka is.

This is a deviation from the brief and it is written down as one rather than taken quietly.

- Anchors: `Dockerfile`, `.dockerignore`, `docs/research/research-architecture.md`

---

## Answered 2026-09-16: option 2, then 3

**keel documents the recipe and does not ship it.** Research §1.5 carries the five paths, the
build-stage rule and the rendered-page acceptance; §2 gains **D8** recording the decision as a
deviation from the brief rather than a quiet omission; the `Dockerfile` has no static variant.

The reasoning that decided it, in one line: a convention that breaks on a Kotlin bump is fixed once
for everyone, and a template that breaks on a Kotlin bump is copied and never updated again — so
sborka's refusal of the same recipe applies here with more force, not less.

**The expiry is [B-18](B-18-scratch-when-static-is-static.md)**, which is what makes this a decision
rather than a refusal: when [KT-89362](https://youtrack.jetbrains.com/issue/KT-89362) lands, `-static`
needs no property overrides and the recipe becomes a linker option. The gconv half does not change
and was never the reason for saying no.
