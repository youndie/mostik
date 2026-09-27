package io.github.youndie.mostik

import kotlin.system.exitProcess

/** The JVM entry point. */
fun main(args: Array<String>): Unit = mostikMain(args)

internal actual fun endProcess(code: Int): Nothing = exitProcess(code)
