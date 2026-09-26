// THE ROOT BUILD EXISTS FOR ONE REASON, AND IT IS NOT STYLE.
//
// `:server` applies the Kotlin multiplatform plugin and `:distribution` applies the Kotlin JVM one.
// Applied only in the subprojects, each arrives in that project's own classloader scope — and the
// Kotlin plugin registers a shared build service, `KotlinNativeBundleBuildService`, which then exists
// twice under two classloaders. The build fails at task-graph time with
//
//   Could not create task ':server:linkReleaseExecutableLinuxX64'
//   > Cannot set the value of ... property 'kotlinNativeBundleBuildService' ... loaded with
//     InstrumentingVisitableURLClassLoader(...project-server) using a provider of type ... loaded
//     with InstrumentingVisitableURLClassLoader(...project-distribution)
//
// naming two classloaders and nothing about what to do. Gradle's own hint is the fix: declaring the
// plugins here with `apply false` loads them once, in the root scope, and both subprojects share it.
//
// This only appeared when the second module arrived, which is why a single-module keel never needed a
// root build file. Every clone that adds a second module meets it.

plugins {
    alias(wip.plugins.kotlinMultiplatform) apply false
    alias(wip.plugins.kotlinJvm) apply false
}
