rootProject.name = "keel"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()

        // WRITTEN OUT BY HAND, AND IT HAS TO BE. `pluginManagement` is evaluated before any settings
        // plugin is applied — including sborka's, which is fetched through it — so the repositories
        // that plugin brings arrive too late to resolve a plugin.
        //
        // FILTERED, and the filter is about failure isolation rather than speed: an unfiltered
        // repository takes part in resolving EVERY dependency, so the day this host is unreachable
        // Gradle disables it and fails artefacts it never served, naming the victim rather than the
        // cause. That has cost this portfolio a debugging session already.
        //
        // This block is what acceptance 1 of the brief means by "with the portfolio's repository
        // configured", and it goes away when kore, sborka and razves reach Maven Central.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("io\\.github\\.youndie.*") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"

    // Repositories with content filters, the shared `wip` catalog and the `.editorconfig` check.
    // Every convention this repository uses comes from here: a flag in keel's own build files is a
    // flag sborka forgot, and it is filed there rather than added here.
    id("io.github.youndie.sborka.settings") version "0.4.0.89"
}

// The whole service: one route, the kore wiring, two entry points.
include(":server")

// The JVM distribution, and nothing else — one `main`, `application` and the AOT cache. Separate
// because `application` and zavarnik are `kotlinJvm`-only and cannot apply to a multiplatform
// module: research D5, and B-03 for what it cost to fit.
//
// NOT `:server-jvm`, WHICH IS THE NAME THE DOCUMENTS USED UNTIL IT WAS BUILT. Kotlin names a
// multiplatform module's JVM artefact `<module>-jvm-<version>.jar`, so `:server`'s is already
// `server-jvm-0.1.0.jar` — and a module called `:server-jvm` produces a jar of exactly that name.
// Both land in the distribution's `lib/` and `installDist` fails with "Entry lib/server-jvm-0.1.0.jar
// is a duplicate". The rename removes the collision and the confusion in one go.
include(":distribution")
