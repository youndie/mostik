// The whole service: the kore wiring, the producer, two entry points. Everything that ships is here.
//
// What is deliberately NOT here: the `application` plugin and the AOT cache — they cannot apply to a
// multiplatform module, so they are `:distribution` — and the image, which is the `Dockerfile`.

plugins {
    alias(wip.plugins.kotlinMultiplatform)
    alias(wip.plugins.kotlinSerialization)
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaNativeService)
    alias(libs.plugins.koreBuild)

    // APPLIED BY THE REPOSITORY, NOT BY SBORKA. `sborka.native-service` configures the gate and
    // refuses the build when `sborka.binaryBudget` is set and this line is missing — a budget
    // nothing checks is a build that passes forever.
    alias(libs.plugins.razves)
}

// BEFORE THE TARGETS, AND IT HAS TO BE. The convention configures `binaries.executable` from inside
// `targets.withType(...).configureEach`, which fires the moment `linuxX64()` declares one — so an
// entry point set after that line is set after it was read, and the build fails with "property
// entryPoint has no value available", naming neither the ordering nor this block.
nativeService {
    entryPoint = "io.github.youndie.mostik.main"
    baseName = "mostik"
}

// THE BUDGET WATCHES WHAT SHIPS. razves holds the release executable to `sborka.binaryBudget` and sets no
// rule for the debug one ("no size rule is set for it, so nothing was checked"), which is the split the
// template used to force by hand for youndie/razves#4. Measured 2026-09-27: release 14,304,424 bytes,
// 11,909,976 under 25 MiB; debug 48,974,392 and unchecked. Nothing deploys the debug binary.

kotlin {
    // Development and tests, and the JVM distribution that ships beside the binary. The parity finding
    // behind the template this came from (keel) is that every service in this portfolio had this line
    // and none had a runnable JVM.
    jvm()

    // The target that ships. `--as-needed`, `fixedBlockPageSize=16` and the staged binary path all
    // arrive from the two conventions above; none of them is a line in this file, which is the
    // arrangement the whole repository exists to demonstrate.
    linuxX64()

    // Off by default — see `mostik.linuxArm64` in gradle.properties.
    if (providers.gradleProperty("mostik.linuxArm64").orNull.toBoolean()) linuxArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kore.core)
            implementation(libs.kore.ktor)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.network)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kafkakn.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.server.test.host)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
