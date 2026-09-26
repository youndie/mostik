package io.github.youndie.mostik

import io.github.youndie.kafkakn.KafkaProducer
import io.github.youndie.kafkakn.ProducerConfig
import io.github.youndie.kafkakn.kafkaProducer
import io.github.youndie.kore.generated.KoreBuildIdentity
import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.kore.ktor.EngineDrain
import io.github.youndie.kore.ktor.installKoreProbes
import io.github.youndie.kore.ktor.installKoreVersion
import io.github.youndie.kore.ktor.installShutdownRefusal
import io.github.youndie.kore.lifecycle.AnnounceNotReady
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.kore.lifecycle.runUntilSignal
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * The whole service, assembled. Nothing here lives inside kore: this is a **consumer writing wiring**,
 * and it is the answer to "does kore own the entry point" — it does not, and this is what that costs
 * in lines.
 */
fun startMostik(settings: MostikSettings) {
    val startup = StartupGate()
    val readiness = ReadinessGate()
    val liveness = LivenessGate()
    val deadlines = ShutdownDeadlines()

    // THE PRODUCER IS BUILT BEFORE ANYTHING SERVES. Construction is where kafkakn refuses a key neither
    // of its arms honours, so a misspelt `KAFKA_*` is a process that does not start rather than a
    // setting silently dropped (research §1.10).
    val producer: KafkaProducer = openProducer(settings)

    val server =
        embeddedServer(
            CIO,
            configure = {
                connectors.add(
                    EngineConnectorBuilder().apply {
                        port = settings.port
                        host = "0.0.0.0"
                    },
                )
                // kore owns these numbers rather than inheriting Ktor's 1000 ms, which is shorter
                // than a great many real requests. The engine still needs them explicitly.
                shutdownGracePeriod = deadlines.drain.inWholeMilliseconds
                shutdownTimeout = deadlines.drain.inWholeMilliseconds + 5_000
            },
            module = { mostikModule(startup, readiness, liveness) },
        )

    // NOT `start(wait = true)`. The main thread has to be free to wait for the signal and then run
    // the sequence — which is the whole reason kore does not go through `addShutdownHook`.
    server.start(wait = false)
    startup.markStarted()

    runBlocking {
        // ONE CALL for the stretch kore owns: wait for the signal, run the sequence, let the process
        // go. What stays here is the registrations, which no API can supply. The call belongs AFTER
        // the server is serving: its default `watch` argument installs the signal handler at the
        // moment of the call, and a handler installed earlier catches a signal whose sequence has
        // nothing to drain. Nothing at this call site shows that.
        runUntilSignal(
            deadlines,
            // INSIDE, not after. On the JVM this call returning means the shutdown hook has returned
            // and the runtime is already terminating, so a line after the call never runs — kore
            // shipped that defect in its own published example and a consumer found it (kore#59).
            onFinished = { run -> println(run.transcript) },
        ) {
            announce(AnnounceNotReady(readiness))
            drain(EngineDrain(server, deadlines.drain, deadlines.drain + 5.seconds))

            // AFTER THE DRAIN, AND NEVER IN `ApplicationStopping` — which runs before the drain on
            // Kotlin/Native and after it on the JVM, from identical source. Closing the producer there
            // takes it out from under a request still waiting for its acknowledgement on one of the two
            // platforms, and the code looks the same on both.
            //
            // `close` flushes: a record whose request was already answered `504` is still handed to the
            // broker here, which is what "unknown" means. How long that takes with the broker gone is B-08.
            pool(
                object : ShutdownParticipant {
                    override val name = "kafka-producer"

                    override suspend fun stop() {
                        producer.close()
                    }
                },
            )
        }
    }
}

/**
 * The producer, or the reason there is none and the process ends.
 *
 * `Exception` and not a named type, because kafkakn's contract promises *that* construction throws on a
 * configuration it refuses, not with which type — and at this boundary the only thing to do with any of them is
 * print the message and stop (rule 3 of [mostikMain]).
 */
private fun openProducer(settings: MostikSettings): KafkaProducer =
    try {
        kafkaProducer(ProducerConfig(settings.producerProperties()))
    } catch (refusal: Exception) {
        refuse("the producer refused its configuration: ${refusal.message}")
    }

/**
 * The routes this service serves, plus everything kore mounts. No route of mostik's own until B-03: what
 * serves today is kore's probes and `/version`.
 */
fun Application.mostikModule(
    startup: StartupGate,
    readiness: ReadinessGate,
    liveness: LivenessGate,
) {
    // BEFORE the probes and the routes. An interceptor installed later would let calls through that
    // arrived first, and the one thing this must never miss is the first request after the announce.
    installShutdownRefusal(isShuttingDown = { readiness.isShuttingDown })
    installKoreProbes(startup, readiness, liveness)
    installKoreVersion(KoreBuildIdentity)

    install(ContentNegotiation) { json() }
}
