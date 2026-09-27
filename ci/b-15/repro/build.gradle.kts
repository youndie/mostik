plugins {
    kotlin("multiplatform") version "2.4.20"
}

kotlin {
    linuxX64 {
        binaries.executable { entryPoint = "main" }
    }
    sourceSets.nativeMain.dependencies {
        implementation("io.ktor:ktor-server-core:3.6.0")
        implementation("io.ktor:ktor-server-cio:3.6.0")
    }
}
