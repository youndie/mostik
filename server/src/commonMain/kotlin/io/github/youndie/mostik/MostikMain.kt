package io.github.youndie.mostik

import io.github.youndie.kore.config.ConfigurationException
import io.github.youndie.kore.config.printConfig
import io.github.youndie.kore.config.systemEnvironment
import io.github.youndie.kore.ktor.requireListenable

/**
 * Everything both entry points do, so the two `main`s stay the one line that genuinely differs.
 *
 * The order is a claim about what a service owes an operator:
 *
 * 1. **`--print-config` answers before anything else.** It is asked most often *because* the process
 *    will not start, so it must not depend on the process starting — and it exits with the same
 *    verdict the start would have given.
 * 2. **The configuration is read once, before anything serves.** A missing value is a process that
 *    does not start, not a route that fails later under a user.
 * 3. **The refusal is the message and nothing else.** A stack trace here buries the two lines that
 *    say which variable and why under frames nobody reading `kubectl logs` wants.
 */
fun mostikMain(args: Array<String>) {
    if (args.any { it == "--print-config" }) {
        val printed = MostikConfig.SCHEMA.printConfig()
        print(printed.text)
        endProcess(printed.exitCode)
    }

    val environment = systemEnvironment()
    val configuration =
        try {
            // A busy port is refused here like a missing variable, not aborted on by CIO later (B-10, keel#49;
            // kore's own check since 0.1.6, B-13).
            MostikConfig.SCHEMA.read(environment).also {
                it.requireListenable(MostikConfig.PORT, reuseAddress = REUSE_ADDRESS)
            }
        } catch (refusal: ConfigurationException) {
            refuse(refusal.message)
        }
    val producerKeys = MostikConfig.kafkaPassThrough(environment).getOrElse { refuse(it.message) }
    val settings = MostikSettings.from(configuration, producerKeys).getOrElse { refuse(it.message) }

    println(settings.describe())
    startMostik(settings)
}

/** Prints the reason, and nothing else, and ends the process — rule 3 above. */
internal fun refuse(message: String?): Nothing {
    println(message)
    endProcess(1)
}

/**
 * Ends the process with [code].
 *
 * **An `expect`/`actual` for something that exists on both targets**, which looks redundant until the
 * metadata compilation says otherwise: `kotlin.system.exitProcess` is declared for the JVM and for
 * Kotlin/Native and **not** in common, so `commonMain` cannot see it. Per-target compilation is
 * happy; `compileCommonMainKotlinMetadata` is where it fails — which is one more reason
 * `./gradlew build` is the gate and a target-by-target compile is not.
 *
 * It is the only `expect`/`actual` pair in this repository, and that is the budget: every pair is two
 * implementations the compiler checks the signature of and never the behaviour of.
 */
internal expect fun endProcess(code: Int): Nothing
