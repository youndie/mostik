package io.github.youndie.keel

import io.github.youndie.kore.config.ConfigurationException
import io.github.youndie.kore.config.printConfig
import io.github.youndie.kore.config.systemEnvironment

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
fun keelMain(args: Array<String>) {
    if (args.any { it == "--print-config" }) {
        val printed = KeelConfig.SCHEMA.printConfig()
        print(printed.text)
        endProcess(printed.exitCode)
    }

    val settings =
        try {
            val configuration = KeelConfig.SCHEMA.read(systemEnvironment())
            KeelSettings(
                port = configuration[KeelConfig.PORT],
                dbPath = configuration[KeelConfig.DB_PATH],
                observed = configuration[KeelConfig.TRACY_ENDPOINT] != null,
            )
        } catch (refusal: ConfigurationException) {
            println(refusal.message)
            endProcess(1)
        }

    println(settings.describe())
    startKeel(settings)
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
