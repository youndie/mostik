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
// against a budget of 100; counting build logic alone it is 81. The four options and why this one:
// https://github.com/youndie/keel/blob/main/docs/backlog/B-03-jvm-half-ships.md
// The follow-up moved most of this file into sborka, because every native service in this portfolio
// that wants a shipped JVM half needs it, which is the definition of something belonging there:
// https://github.com/youndie/keel/blob/main/docs/backlog/B-17-adopt-the-jvm-distribution-convention.md

plugins {
    alias(wip.plugins.kotlinJvm)
    alias(libs.plugins.sborkaJvmDistribution)

    // APPLIED BY THE REPOSITORY, NOT BY THE CONVENTION, and for the reasons sborka gives: the version
    // belongs to the repository whose build it is, and a convention that carried zavarnik would put
    // it on the build classpath of every repository taking any sborka convention.
    alias(libs.plugins.zavarnik)
}

dependencies { implementation(project(":server")) }

// THE LINES THAT ARE THIS SERVICE'S. Everything else this module used to say — `application`, the
// toolchain, the module-name collision guard, zavarnik's readiness default — is
// `sborka.jvm-distribution` now (sborka#78, and the item above). The workload stays here because what is worth
// training a cache on is a property of the service rather than of the shape.
jvmDistribution {
    mainClass = "io.github.youndie.mostik.jvm.MainKt"
}

zavarnik {
    training {
        // THE TWO REQUIRED KEYS, pointing at nothing. Training starts the distribution, and mostik refuses
        // to start without somewhere to publish and something it may publish to. Constructing the
        // producer contacts no broker that training needs to exist.
        //
        // THE WORKLOAD IS `/version`, AND THE PUBLISHING PATH IS NOT IN THE CACHE. Training runs with no
        // broker, so a publish here would wait out `max.block.ms` and train the refusal path instead. A
        // workload that publishes needs a broker beside the build, which nothing provides yet.
        environment("MOSTIK_BOOTSTRAP_SERVERS", "127.0.0.1:9092")
        environment("MOSTIK_TOPICS", "orders")
        workload { get("http://127.0.0.1:8080/version") }
    }
}
