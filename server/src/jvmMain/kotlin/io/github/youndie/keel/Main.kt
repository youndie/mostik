package io.github.youndie.keel

import kotlin.system.exitProcess

/** The JVM entry point. */
fun main(args: Array<String>): Unit = keelMain(args)

internal actual fun endProcess(code: Int): Nothing = exitProcess(code)
