---
id: B-21
title: "Build with both native targets, now that staging can tell them apart"
status: done
priority: P2
size: S
stage: m1-ships-twice
epic: feature-item-round-trip
---

# B-21 — The AC B-15 could not meet

[sborka#80](https://github.com/youndie/sborka/issues/80) is fixed and published in `0.4.0.82`:
`stageNativeImage` stages one binary per native target, and `NativeImageReference` takes the staged
path so a generated Dockerfile names the architecture it was built for.

B-15 closed on the thing it was for — an arm64 suite running on arm64 hardware — and left one
criterion unmet: `./gradlew build -Pkeel.linuxArm64=true` failed in staging, because two release
binaries were renamed to one name in one directory. This is that criterion.

- **What has to be checked beyond a green build.** The staged layout changes shape when a second
  target appears, and keel's `Dockerfile` has a `COPY` line reading `build/native-image/keel`. If the
  layout is now `<konanTarget>/keel` whenever there are two, that `COPY` breaks the moment a clone
  turns `keel.linuxArm64` on — which is a worse failure than the one being fixed, because it happens
  at image build time rather than at configuration.
- The rejected alternative is leaving the third target unbuildable and keeping the note. It costs
  nothing today and it means the first clone to want arm64 finds a repository that documents the
  problem instead of one that solved it.

- AC: `./gradlew build -Pkeel.linuxArm64=true` is green.
- AC: the image still builds **with the property off**, and what happens to that `COPY` with the
  property on is either verified or written down.
- Anchors: `gradle/libs.versions.toml`, `Dockerfile`, `server/build.gradle.kts`

---

## Iteration 1 — 2026-09-16, done

sborka `0.4.0.82` taken. `./gradlew build -Pkeel.linuxArm64=true` is **green**, and both binaries are
staged with their own `NEEDED` report:

```
stageNativeImage: keel declares 7 — libm libpthread librt libdl libgcc_s libc ld-linux-x86-64
stageNativeImage: keel declares 5 — libm libpthread libdl libgcc_s libc
```

**arm64 declares five shared libraries where x86-64 declares seven** — `librt` and the x86-64 loader
are simply not in its list. Nothing follows from it for keel, whose image is amd64, but it is the
kind of difference a `COPY` line would discover at the wrong moment.

### The check that mattered was not the green build

The item's second criterion was written because a fix can move a problem later rather than remove it,
and this one does:

| `keel.linuxArm64` | staged layout |
|---|---|
| off (what keel ships) | `build/native-image/keel` |
| on | `build/native-image/linux_x64/keel`, `.../linux_arm64/keel` |

keel's `Dockerfile` has `COPY --from=build /app/server/build/native-image/keel`. It is **correct for
what keel ships** — the image was rebuilt to prove it, 13 972 513 bytes — and it **breaks the moment a
clone turns the property on**, at image build time, with a "not found" that names the path and nothing
about the property that moved it. That is later and more expensive than the configuration error this
whole thread started from.

Handled where the person editing will be: a comment at the `COPY` line itself, and quirk 19. **Not
handled with a `--build-arg`**, which would add surface to every clone for a case keel does not ship —
the same argument B-16 made about `STATIC=1`, and it holds here for a smaller prize.

### What this closes

B-15's second criterion, left open when that item closed on the suite it was actually for. Both native
targets now build, and `linuxArm64` is tested on hardware of its own architecture.
