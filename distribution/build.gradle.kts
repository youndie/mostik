// The JVM half, as a distribution that ships rather than a target that only compiles.
//
// It exists because `application` and zavarnik are `kotlinJvm`-only and do not apply to a
// multiplatform module — research D5. It holds one `main` and no logic: a starter whose logic lived
// in a JVM-only module would have quietly stopped shipping twice.
//
// THE MODULE IS `:distribution` AND NOT `:server-jvm`, WHICH EVERY DOCUMENT CALLED IT UNTIL IT WAS
// BUILT. `:server`'s JVM artefact is already `server-jvm-0.1.0.jar` — Kotlin names it that — so a
// module of that name produces a duplicate in `lib/` and `installDist` refuses. The name also reads
// better: this module is a distribution, not a target.
//
// THESE TWELVE LINES ARE THE ONES ACCEPTANCE 6 ARGUED ABOUT, and the argument is worth knowing when
// you edit them. Counting the version catalog as build logic, this module put the repository at 115
// against a budget of 100; counting build logic alone it is 81. B-03 has the four options and why
// this one. The follow-up is B-17: every native service in this portfolio that wants a shipped JVM
// half needs this same file, which is the definition of something belonging in sborka.

plugins {
    alias(wip.plugins.kotlinJvm)
    alias(libs.plugins.sborkaJvmDistribution)

    // APPLIED BY THE REPOSITORY, NOT BY THE CONVENTION, and for the reasons sborka gives: the version
    // belongs to the repository whose build it is, and a convention that carried zavarnik would put
    // it on the build classpath of every repository taking any sborka convention.
    alias(libs.plugins.zavarnik)
}

dependencies { implementation(project(":server")) }

// THE THREE LINES THAT ARE KEEL'S. Everything else this module used to say — `application`, the
// toolchain, the module-name collision guard, zavarnik's readiness default — is
// `sborka.jvm-distribution` now (B-17, sborka#78). The workload stays here because what is worth
// training a cache on is a property of the service rather than of the shape.
jvmDistribution {
    mainClass = "io.github.youndie.keel.jvm.MainKt"
}

zavarnik {
    training {
        // THE TRAINING RUN GETS ITS OWN DATABASE, and the line exists to stop the run leaving one
        // where the distribution is assembled. `KEEL_DB_PATH` is relative by default, the start
        // script runs from `build/install/distribution`, so training used to create a database
        // inside the thing a Dockerfile might copy — and a clone shipping `installDist` would ship
        // the training run's rows. zavarnik#13 is what made this expressible; before it the training
        // run inherited the build's environment and nothing else.
        // INTO `build/` ITSELF, not a subdirectory of it. `mode=rwc` creates the database FILE and
        // not its parent, so a path through a directory that does not exist yet fails at startup with
        // a raw JDBC stack trace and no mention of the directory — which is exactly how this line was
        // first written and what it cost to find out.
        environment("KEEL_DB_PATH", layout.buildDirectory.file("aot-train-keel.db").get().asFile.path)
        workload { get("http://127.0.0.1:8080/items") }
    }
}
