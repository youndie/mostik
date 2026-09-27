plugins {
    kotlin("multiplatform") version "2.4.20"
}

kotlin {
    // The same server on the JVM, as the control: mostik's JVM build lost no answer in 746 799 requests.
    jvm {
        mainRun { mainClass = "MainKt" }
    }
    linuxX64 {
        binaries.executable { entryPoint = "main" }
    }
    sourceSets.commonMain.dependencies {
        implementation("io.ktor:ktor-server-core:3.6.0")
        implementation("io.ktor:ktor-server-cio:3.6.0")
    }
}

// For load.sh's JVM control: the runtime classpath written to a file, so the server runs without the `application`
// plugin a multiplatform build cannot have.
tasks.register("writeJvmRuntimeClasspath") {
    val classpath = configurations.named("jvmRuntimeClasspath")
    val out = layout.buildDirectory.file("jvmRuntimeClasspath.txt")
    inputs.files(classpath)
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.get().files.joinToString(":")) }
}
