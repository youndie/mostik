package io.github.youndie.keel

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import io.github.youndie.keel.item.ItemStore
import io.github.youndie.keel.item.SqliteItemStore
import io.github.youndie.keel.item.itemRoutes
import io.github.youndie.keel.item.itemsSchema
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
fun startKeel(settings: KeelSettings) {
    val startup = StartupGate()
    val readiness = ReadinessGate()
    val liveness = LivenessGate()
    val deadlines = ShutdownDeadlines()

    // THE DATABASE IS OPENED BEFORE ANYTHING SERVES, and the schema is applied before that. A
    // migration that runs after the first request is a migration racing a user.
    //
    // The URL is built by a named function rather than inline, and that is a scar: this line once
    // held a broken template, so the service opened a database at a path named after the expression
    // that should have produced it. It answered every request correctly and persisted nothing —
    // a restart came back empty. The store suite could not catch it, because the suite builds its
    // own URL; only running the binary twice could. `keelDatabaseUrl` is now one thing, tested.
    val db: Driver =
        sqlite(
            url = keelDatabaseUrl(settings.dbPath),
            options =
                ConnectionPool.Options
                    .builder()
                    .maxConnections(POOL_SIZE)
                    .build(),
        )
    val store: ItemStore = SqliteItemStore(db)
    runBlocking { itemsSchema().forEach { db.execute(it).getOrThrow() } }

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
            module = { keelModule(startup, readiness, liveness, store) },
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
            // Kotlin/Native and after it on the JVM, from identical source. Closing the pool there
            // takes the connection out from under a request still being served on one of the two
            // platforms, and the code looks the same on both.
            pool(
                object : ShutdownParticipant {
                    override val name = "sqlite"

                    override suspend fun stop() {
                        db.close()
                    }
                },
            )
        }
    }
}

/** The routes this service serves, plus everything kore mounts. */
fun Application.keelModule(
    startup: StartupGate,
    readiness: ReadinessGate,
    liveness: LivenessGate,
    store: ItemStore,
) {
    // BEFORE the probes and the routes. An interceptor installed later would let calls through that
    // arrived first, and the one thing this must never miss is the first request after the announce.
    installShutdownRefusal(isShuttingDown = { readiness.isShuttingDown })
    installKoreProbes(startup, readiness, liveness)
    installKoreVersion(KoreBuildIdentity)

    install(ContentNegotiation) { json() }
    itemRoutes(store)
}

/**
 * Where the database is, as sqlx4k wants it.
 *
 * **`mode=rwc` asks for the file to be created when it is not there.**
 *
 * It is kept as a statement of intent rather than as a fix, and the difference is worth the line
 * because this comment first claimed the opposite. It said the Rust driver would not create the file
 * and Xerial's JDBC would, so the parameter was what made one line work on both. **Measured on
 * sqlx4k 1.13.1, that is not true**: the `linuxX64` binary creates a missing database with the
 * parameter removed, exactly as the JVM half does. The claim came from reasoning about a default,
 * not from running anything.
 *
 * What it is worth keeping for is that neither driver documents the default as part of its contract,
 * and they are two different drivers — so a build that depends on them agreeing depends on something
 * nobody promised. Saying it costs nine characters.
 *
 * A function rather than an interpolation at the call site because it is the piece that was wrong
 * once and is worth a test. `KeelDatabaseUrlTest` is that test — and what it guards is the
 * interpolation, not the driver's behaviour.
 */
internal fun keelDatabaseUrl(path: String): String = "sqlite://$path?mode=rwc"

/**
 * Two connections, not one.
 *
 * One deadlocks the moment anything holds a transaction open while the code inside asks for a second
 * connection, and that shape arrives with a clone's first feature rather than being exotic. A
 * starting point to measure, not a tuned number.
 */
private const val POOL_SIZE = 2
