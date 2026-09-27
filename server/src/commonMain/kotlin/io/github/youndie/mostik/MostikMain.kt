package io.github.youndie.mostik

import io.github.youndie.kore.config.ConfigurationException
import io.github.youndie.kore.config.printConfig
import io.github.youndie.kore.config.systemEnvironment
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

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
            MostikConfig.SCHEMA.read(environment)
        } catch (refusal: ConfigurationException) {
            refuse(refusal.message)
        }
    val producerKeys = MostikConfig.kafkaPassThrough(environment).getOrElse { refuse(it.message) }
    val settings = MostikSettings.from(configuration, producerKeys).getOrElse { refuse(it.message) }

    println(settings.describe())
    portProblem(settings.port)?.let { refuse(it) }
    startMostik(settings)
}

/**
 * Why [port] cannot be listened on, or `null` when it can.
 *
 * mostik binds the port once itself, and closes it, before the server does, because a busy port was not a refusal
 * (B-10). The native build aborted: CIO binds inside a coroutine of its own, and an exception there has no handler
 * on Kotlin/Native, so the process died with `SIGABRT` and 59 lines of stack. The JVM build exited 1, with 18.
 * Neither said `MOSTIK_PORT`.
 *
 * Something else can take the port in the moment between this check and the server's own bind. This makes the
 * common case a sentence; it does not make the race impossible, and the address the fix belongs to is Ktor's CIO.
 */
internal fun portProblem(port: Int): String? =
    runBlocking {
        try {
            SelectorManager().use { selector -> aSocket(selector).tcp().bind("0.0.0.0", port).close() }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            "MOSTIK_PORT ($port) cannot be listened on: ${failure.message}"
        }
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
