# keel's image. Two stages, and the pair is chosen together: a binary linked against the builder's
# glibc will not start on a runtime with an older one, and the failure is the container exiting
# before any of the application's own logging has run.
#
# This is sborka's reference file with the two holes filled — `./gradlew :server:writeNativeDockerfile`
# writes it, once, and then it is the repository's. It is committed rather than generated because the
# runtime image is a decision about certificates, shared libraries and a base image's glibc, and
# those belong in a file a person reads and a pull request reviews.

FROM --platform=linux/amd64 gradle:9.7.1-jdk25-noble AS build
WORKDIR /app
COPY . .
# The Kotlin/Native toolchain is ~1 GB and is downloaded on a cold build. Cached across image builds
# rather than fetched every time; `sharing=locked` because two concurrent builds writing the same
# cache corrupt it.
RUN --mount=type=cache,target=/root/.konan,sharing=locked \
    ./gradlew :server:stageNativeImage --no-daemon

# `distroless/cc` and not `distroless/base`, and the reason is not glibc: Kotlin/Native's exception
# handling imports thirteen `_Unwind_*` symbols from `libgcc_s`, which `base` does not carry. `base`
# fails at exec with `cannot open shared object file`.
FROM gcr.io/distroless/cc-debian13
# `ca-certificates` is not a library, so `ldd` on the binary will never name it. Without it every
# outbound TLS call fails with a message about a certificate path and nothing about this line.
# distroless/cc carries them already — kept as a comment because the first thing anyone does with
# this file is swap the base image.
#
# NOTHING ELSE IS COPIED, and that is load-bearing. A Kotlin/Native binary declares `libcrypt.so.1`,
# which this base image does not have, and imports nothing from it; older versions of this file
# copied it out of the builder and carried a rule that the builder's glibc must be no newer than the
# runtime's. `sborka.kmp` links Linux executables with `--as-needed`, the declaration goes away, and
# so does the rule. Measured on this binary: **seven** NEEDED entries, not ten. If this image ever
# fails with `cannot open shared object file`, the answer is that the convention did not apply — not
# another COPY line.
#
# THE SECOND ALLOCATOR, one floor below the one `sborka.native-service` caps in the binary.
# Underneath Kotlin/Native's allocator sits glibc's malloc, and it hands a thread an arena of its own
# whenever the one it wants is busy — up to eight per HOST core. It does not see the container's
# quota, so `--cpus=1` on a twenty-core runner still allows 160 arenas, each returning pages only
# from its top.
#
# NOT TOGETHER WITH `-Xallocator=std` WITHOUT A MEASUREMENT. Ten runs per arm at 2 000 rps under
# 512 MiB: the Kotlin allocator with this cap peaked at 62.8 MB and survived 10/10; `-Xallocator=std`
# WITH this cap peaked at 413.7 MB and survived 7/10, where the same allocator uncapped peaked at
# 39.3 MB and survived every run. Two settings, each harmless alone, fatal together. A service that
# switches allocator re-measures this line or deletes it.
#
# 2 IS A MEASURED NUMBER AND IT DOES NOT TRANSFER BY ITSELF. On katcher it took the peak from 65.3 MB
# to 62.8 MB; on a service without a database it went the other way. Measure on the service that will
# ship it — with a positive control, since a harness that cannot detect a regression reports its
# absence. What is not worth doing is leaving it unset because nobody measured: the default is the
# number that gets a service killed under a limit.
ENV MALLOC_ARENA_MAX=2
# THE PATH CHANGES SHAPE IF A SECOND NATIVE TARGET IS TURNED ON, and this line is where that bites.
#
# `stageNativeImage` stages flat — `native-image/keel` — while the module declares one native target,
# and per-target — `native-image/linux_x64/keel`, `native-image/linux_arm64/keel` — as soon as it
# declares two. That is right: one binary per target under one name would be a `COPY` that finds a
# file and the wrong one.
#
# keel ships with `keel.linuxArm64=false`, so the flat path is correct here. **A clone that turns it
# on changes this line to `native-image/linux_x64/keel`** — or to `linux_arm64` with a builder stage
# platform to match, since `FROM --platform=linux/amd64` above decides which binary this image wants.
# Without that edit the image build fails with "not found" naming the path and nothing about the
# property that moved it.
COPY --from=build /app/server/build/native-image/keel /app/keel

# EXEC FORM, ALWAYS. Shell form makes `/bin/sh -c` PID 1, and it does not forward `SIGTERM` — so the
# process never sees the signal, the ordered shutdown never runs, and the container looks like it
# stopped instantly and cleanly. That is the one thing this whole repository exists to get right.
ENTRYPOINT ["/app/keel"]
