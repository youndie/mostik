package io.github.youndie.mostik

import kotlin.system.exitProcess

/** The JVM entry point. */
fun main(args: Array<String>) {
    keepKtorOutOfTheShutdown()
    mostikMain(args)
}

/**
 * Switches off Ktor's own JVM shutdown hook, so that kore's sequence is the only thing a `SIGTERM` starts.
 *
 * A WORKAROUND WITH AN ADDRESS: youndie/kore#90. On the JVM, Ktor's `EmbeddedServer.start` registers
 * `addShutdownHook { stop() }` unconditionally, and the JVM runs every shutdown hook at once. So the engine
 * stopped listening 1 ms after the signal, while kore was still announcing: readiness never answered `503`, the
 * connection was refused instead (B-12, measured; reproduced on keel's own JVM distribution). kore already
 * stops the engine itself, in its drain.
 *
 * Ktor reads the property into a top-level `val` the first time `ShutdownHookJvm` loads, which is at
 * `start()`. So this has to run before the server exists, and it is the first line of every JVM `main`. It
 * goes when kore does it.
 */
fun keepKtorOutOfTheShutdown() {
    System.setProperty("io.ktor.server.engine.ShutdownHook", "false")
}

internal actual fun endProcess(code: Int): Nothing = exitProcess(code)
