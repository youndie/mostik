package io.github.youndie.mostik

import io.github.youndie.kafkakn.Delivery
import io.github.youndie.kafkakn.KafkaProducer
import io.github.youndie.kafkakn.ProducerConfig
import io.github.youndie.kafkakn.ProducerRecord
import io.github.youndie.kafkakn.kafkaProducer
import io.github.youndie.kore.generated.KoreBuildIdentity
import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.kore.ktor.EngineDrain
import io.github.youndie.kore.ktor.installKoreProbes
import io.github.youndie.kore.ktor.installKoreVersion
import io.github.youndie.kore.ktor.installShutdownRefusal
import io.github.youndie.kore.ktor.startForKore
import io.github.youndie.kore.lifecycle.AnnounceNotReady
import io.github.youndie.kore.lifecycle.DrainGate
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.kore.lifecycle.runUntilSignal
import io.github.youndie.mostik.publish.publishRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole service, assembled. Nothing here lives inside kore: this is a **consumer writing wiring**,
 * and it is the answer to "does kore own the entry point" — it does not, and this is what that costs
 * in lines.
 */
fun startMostik(settings: MostikSettings) {
    val startup = StartupGate()
    val readiness = ReadinessGate()
    // The latch the refusal reads and the drain opens: one instance, handed to both (kore 0.1.7, B-14). Through the
    // announce the service goes on serving while readiness says 503, so a proxy stops sending and what it already
    // sent gets its answer. The refusal used to read readiness and answered 503 from the first millisecond.
    val draining = DrainGate()
    val liveness = LivenessGate()
    // The drain is the deployment's (MOSTIK_DRAIN_MS), and the start-up has already refused one shorter than the
    // publish deadline plus its margin (B-07). The other deadlines stay kore's.
    val deadlines = ShutdownDeadlines(drain = settings.drainMs.milliseconds)

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
                reuseAddress = REUSE_ADDRESS
            },
            module = { mostikModule(startup, readiness, liveness, draining, settings) { producer.enqueue(it) } },
        )

    // NOT `start(wait = true)`. The main thread has to be free to wait for the signal and then run
    // the sequence — which is the whole reason kore does not go through `addShutdownHook`.
    // `startForKore` is `start(wait = false)` with Ktor's own JVM shutdown hook switched off first: the JVM runs that
    // hook beside kore's, and it stopped the engine mid-announce (B-12, kore#90; kore's since 0.1.6, B-13).
    server.startForKore()
    startup.markStarted()

    runBlocking {
        // ONE CALL for the stretch kore owns: wait for the signal, run the sequence, let the process
        // go. What stays here is the registrations, which no API can supply. The call belongs AFTER
        // `startForKore`, and it MUST come: on native, since kore 0.1.9, `startForKore` holds the
        // signal from before `start` with a handler that only records it, so that a signal in the
        // first moments no longer meets Ktor's own handler and hangs (kore B-63, mostik B-16). This
        // call is what runs the sequence for a signal recorded that early. Nothing at this call site
        // shows that.
        runUntilSignal(
            deadlines,
            // INSIDE, not after. On the JVM this call returning means the shutdown hook has returned
            // and the runtime is already terminating, so a line after the call never runs — kore
            // shipped that defect in its own published example and a consumer found it (kore#59).
            onFinished = { run -> println(run.transcript) },
        ) {
            announce(AnnounceNotReady(readiness))
            drain(EngineDrain(server, deadlines.drain, deadlines.drain + 5.seconds, draining))

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

/** The routes this service serves, plus everything kore mounts. */
fun Application.mostikModule(
    startup: StartupGate,
    readiness: ReadinessGate,
    liveness: LivenessGate,
    draining: DrainGate,
    settings: MostikSettings,
    enqueue: suspend (ProducerRecord) -> Delivery,
) {
    // BEFORE the probes and the routes. An interceptor installed later would let calls through that
    // arrived first, and the one thing this must never miss is the first request after the drain opens.
    installShutdownRefusal(draining)
    installKoreProbes(startup, readiness, liveness)
    installKoreVersion(KoreBuildIdentity)

    install(ContentNegotiation) { json() }
    routing {
        publishRoutes(
            topics = settings.topics,
            maxRecordBytes = settings.maxRecordBytes,
            deadline = settings.publishDeadlineMs.milliseconds,
            retryAfterSeconds = retryAfterSeconds(settings.queueWaitMs),
            enqueue = enqueue,
        )
    }
}

/**
 * What `Retry-After` says on a `429`: the queue wait, in whole seconds and never less than one.
 *
 * A record is refused after waiting `MOSTIK_QUEUE_WAIT_MS` for room or for metadata, so a retry sooner than that
 * asks the same full queue again. A fixed value, not one derived from how fast the queue drains: nothing here
 * measures the drain, and a number derived from nothing would look like a measurement (research, open question 2).
 */
internal fun retryAfterSeconds(queueWaitMs: Int): Int = maxOf(1, (queueWaitMs + 999) / 1_000)

/**
 * `SO_REUSEADDR` on the listening socket, for the engine and for the start-up's port check alike.
 *
 * **On, because native refused to restart over its own TIME_WAIT.** After 20 requests with `Connection: close`,
 * a restart on the same port was refused on native three times out of three and served on the JVM three times
 * out of three (`ci/b-13/run.sh`, B-13). CIO applies `reuseAddress = false` literally on Kotlin/Native, while the
 * JVM's NIO sets `SO_REUSEADDR` itself; keel found the same (`youndie/keel@e6a12eb`). A port another process is
 * *listening* on is still refused: `ci/b-10/run.sh`.
 *
 * One constant, because the check and the engine must bind the same way. A check stricter than the engine refuses
 * a start that would have worked; a looser one lets through a start that aborts. kore's `requireListenable` has
 * defaulted to `true` since 0.1.8 (kore B-62), and the constant is still passed to it, so that the two cannot drift
 * apart when either default moves.
 */
internal const val REUSE_ADDRESS: Boolean = true
