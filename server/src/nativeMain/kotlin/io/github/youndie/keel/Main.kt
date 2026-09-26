package io.github.youndie.keel

import kotlin.system.exitProcess

/**
 * The Kotlin/Native entry point, named in `build.gradle.kts` as `entryPoint`.
 *
 * In `nativeMain` rather than in `linuxX64Main`, so that turning `keel.linuxArm64` on is a line in
 * `gradle.properties` and not a second copy of this file. A duplicated entry point is two programs
 * that can diverge, which is the thing a template must not teach.
 */
fun main(args: Array<String>): Unit = keelMain(args)

internal actual fun endProcess(code: Int): Nothing = exitProcess(code)
