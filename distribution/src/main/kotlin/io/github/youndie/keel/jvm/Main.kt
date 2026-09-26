package io.github.youndie.keel.jvm

import io.github.youndie.keel.keelMain

/**
 * The distribution's entry point, and the whole module.
 *
 * `:server` already has a `jvmMain` `main` for its own target; this one exists so that `application`
 * — which cannot see a multiplatform module — has a class to name. Anything that grows here has
 * stopped being template renaming and belongs in `:server`, where both targets can reach it.
 */
fun main(args: Array<String>): Unit = keelMain(args)
