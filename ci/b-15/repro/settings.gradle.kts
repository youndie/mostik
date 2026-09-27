// B-15's minimal reproduction: Ktor's CIO on linuxX64 and nothing else. A standalone build, not part of mostik's:
// no kore, no kafkakn, no sborka, so what it shows is Ktor's alone.
rootProject.name = "b15-repro"

pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
