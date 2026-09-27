package io.github.youndie.mostik.jvm

import io.github.youndie.mostik.keepKtorOutOfTheShutdown
import io.github.youndie.mostik.mostikMain

/**
 * The distribution's entry point, and the whole module.
 *
 * `:server` already has a `jvmMain` `main` for its own target; this one exists so that `application`
 * — which cannot see a multiplatform module — has a class to name. Anything that grows here has
 * stopped being template renaming and belongs in `:server`, where both targets can reach it.
 */
fun main(args: Array<String>) {
    // First, before the server exists: Ktor's JVM shutdown hook would stop the engine at SIGTERM (kore#90, B-12).
    keepKtorOutOfTheShutdown()
    mostikMain(args)
}
