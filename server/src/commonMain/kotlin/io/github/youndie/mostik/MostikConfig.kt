package io.github.youndie.mostik

import io.github.youndie.kore.config.ConfigKey
import io.github.youndie.kore.config.ConfigPair
import io.github.youndie.kore.config.ConfigSchema
import io.github.youndie.kore.config.Configuration
import io.github.youndie.kore.config.Environment
import io.github.youndie.kore.config.EnvironmentNames

/**
 * What this service is configured as, under the prefix `MOSTIK`, plus the producer's own keys under `KAFKA`.
 *
 * Two prefixes, and the split is forced rather than chosen (research §1.10). kore's schema refuses a variable
 * under its prefix that it does not declare, so an open-ended set of producer keys cannot live under `MOSTIK_`.
 * They come from `KAFKA_*` instead, outside the schema, and a misspelt one is still refused at start-up — by
 * kafkakn, which refuses a key neither of its arms honours when the producer is constructed.
 */
object MostikConfig {
    val PORT: ConfigKey<Int> = ConfigKey.int("PORT", default = 8080)

    /** Kafka's `bootstrap.servers`. Required: a bridge with nowhere to publish should not start. */
    val BOOTSTRAP_SERVERS: ConfigKey<String> = ConfigKey.required("BOOTSTRAP_SERVERS")

    /** The allowlist, comma-separated. Any other topic is `404` (research D4). */
    val TOPICS: ConfigKey<String> = ConfigKey.required("TOPICS")

    /** The bound on one publish, in milliseconds. Enforced by B-05; read and checked from B-01 on. */
    val PUBLISH_DEADLINE_MS: ConfigKey<Int> = ConfigKey.int("PUBLISH_DEADLINE_MS", default = 5_000)

    /**
     * How long `enqueue` may wait for room in the queue or for the topic's metadata: the producer's `max.block.ms`
     * (B-05). A record not queued within it is `429`. It must be shorter than the deadline, or nothing is left for
     * the broker's answer.
     */
    val QUEUE_WAIT_MS: ConfigKey<Int> = ConfigKey.int("QUEUE_WAIT_MS", default = 1_000)

    /**
     * How long the engine drains on `SIGTERM`: kore's `ShutdownDeadlines.drain`, whose default this repeats (B-07).
     *
     * A key because the grace period is the deployment's, not kore's: `docker stop` gives 10 s, and kore's default
     * deadlines add up to 29 s (kore's own KDoc on `gracePeriod`). A deployment that shrinks the drain to fit is
     * exactly the one the budget check below exists for.
     */
    val DRAIN_MS: ConfigKey<Int> = ConfigKey.int("DRAIN_MS", default = 15_000)

    /** A larger body is `413` before the producer is called (B-03). One MiB, Kafka's own default message size. */
    val MAX_RECORD_BYTES: ConfigKey<Int> = ConfigKey.int("MAX_RECORD_BYTES", default = 1_048_576)

    /** Half of the observability pair. Unset means "not observed", which is a decision. */
    val TRACY_ENDPOINT: ConfigKey<String?> = ConfigKey.optional("TRACY_ENDPOINT")

    /** The other half, and a secret. */
    val TRACY_KEY: ConfigKey<String?> = ConfigKey.optional("TRACY_KEY", secret = true)

    val SCHEMA: ConfigSchema =
        ConfigSchema(
            prefix = "MOSTIK",
            keys =
                listOf(
                    PORT,
                    BOOTSTRAP_SERVERS,
                    TOPICS,
                    PUBLISH_DEADLINE_MS,
                    QUEUE_WAIT_MS,
                    DRAIN_MS,
                    MAX_RECORD_BYTES,
                    TRACY_ENDPOINT,
                    TRACY_KEY,
                ),
            pairs = listOf(ConfigPair(TRACY_ENDPOINT.name, TRACY_KEY.name)),
        )

    /** The prefix of the producer keys that pass through, outside the schema. */
    const val KAFKA_PREFIX: String = "KAFKA_"

    /**
     * How much longer than the deadline the drain has to be. A request's answer is written after its deadline
     * expires, and B-05 measured that at 11 to 22 ms past it, so a second is a wide margin, not a tight one.
     */
    const val DRAIN_MARGIN_MS: Int = 1_000

    /**
     * Producer keys mostik sets itself. Taking them from `KAFKA_*` as well would give one value two sources, and
     * the day they disagree nothing says which one won.
     */
    private val OWNED: Map<String, String> =
        mapOf(
            "bootstrap.servers" to "MOSTIK_BOOTSTRAP_SERVERS",
            "max.block.ms" to "MOSTIK_QUEUE_WAIT_MS",
        )

    /**
     * Every `KAFKA_*` variable as the producer key it names: `KAFKA_SSL_CA_LOCATION` → `ssl.ca.location`.
     *
     * Underscores become dots, so a Kafka key whose own name holds an underscore cannot be written this way.
     * None is in kafkakn's portable set (research §1.10).
     *
     * An environment that cannot be listed is a refusal, not an empty map. mostik ships no target where that
     * happens, and a pass-through that silently passed nothing is a configuration that looks applied and is not.
     */
    fun kafkaPassThrough(environment: Environment): Result<Map<String, String>> {
        val names =
            when (val listed = environment.names()) {
                is EnvironmentNames.Listed -> {
                    listed.names
                }

                is EnvironmentNames.Unavailable -> {
                    return Result.failure(
                        IllegalStateException("${KAFKA_PREFIX}* cannot be read here: ${listed.reason}"),
                    )
                }
            }
        val keys =
            names
                .filter { it.startsWith(KAFKA_PREFIX) && it.length > KAFKA_PREFIX.length }
                .associate { name ->
                    name.removePrefix(KAFKA_PREFIX).lowercase().replace('_', '.') to
                        (environment.lookup(name) ?: "")
                }
        val clashes = keys.keys.filter { it in OWNED }
        if (clashes.isNotEmpty()) {
            return Result.failure(
                IllegalStateException(
                    clashes.joinToString("\n") { key ->
                        "${KAFKA_PREFIX}${key.uppercase().replace('.', '_')} is not read: " +
                            "$key comes from ${OWNED.getValue(key)}"
                    },
                ),
            )
        }
        return Result.success(keys)
    }
}

/**
 * The resolved configuration in the shape the rest of the service uses.
 *
 * A small class rather than passing `Configuration` around, so a value nothing reads shows up as an
 * unused property here instead of hiding behind an indexing call that may never be made.
 */
class MostikSettings(
    val port: Int,
    val bootstrapServers: String,
    val topics: Set<String>,
    val publishDeadlineMs: Int,
    val queueWaitMs: Int,
    val drainMs: Int,
    val maxRecordBytes: Int,
    val observed: Boolean,
    val producerKeys: Map<String, String>,
) {
    /** The properties the producer is constructed with: the pass-through first, then what mostik owns. */
    fun producerProperties(): Map<String, String> =
        producerKeys + mapOf("bootstrap.servers" to bootstrapServers, "max.block.ms" to queueWaitMs.toString())

    /**
     * One line for `docker logs`, naming what was configured. Producer keys are listed by name only: some of them
     * are credentials (`sasl.password`), and a value printed once is a value in every log store it reaches.
     */
    fun describe(): String =
        "configured: port=$port bootstrap=$bootstrapServers topics=${topics.sorted().joinToString(",")} " +
            "deadline=${publishDeadlineMs}ms queueWait=${queueWaitMs}ms drain=${drainMs}ms " +
            "maxRecord=${maxRecordBytes}B " +
            "producerKeys=${producerKeys.keys.sorted()} observability=${if (observed) "on" else "off"}"

    companion object {
        /**
         * The settings, or every reason they cannot be had.
         *
         * [Configuration] has already refused what kore can see; what is left is what only mostik knows the
         * meaning of — an allowlist with nothing in it, a non-positive bound.
         */
        fun from(
            configuration: Configuration,
            producerKeys: Map<String, String>,
        ): Result<MostikSettings> {
            val topics =
                configuration[MostikConfig.TOPICS]
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            val problems =
                buildList {
                    if (topics.isEmpty()) add("MOSTIK_TOPICS names no topic: every publish would be 404")
                    if (configuration[MostikConfig.PUBLISH_DEADLINE_MS] <= 0) {
                        add("MOSTIK_PUBLISH_DEADLINE_MS must be positive")
                    }
                    val deadline = configuration[MostikConfig.PUBLISH_DEADLINE_MS]
                    val queueWait = configuration[MostikConfig.QUEUE_WAIT_MS]
                    if (queueWait <= 0) add("MOSTIK_QUEUE_WAIT_MS must be positive")
                    if (queueWait >= deadline) {
                        add(
                            "MOSTIK_QUEUE_WAIT_MS ($queueWait) must be shorter than MOSTIK_PUBLISH_DEADLINE_MS " +
                                "($deadline): nothing would be left of the deadline for the broker's answer",
                        )
                    }
                    // A request in flight at SIGTERM needs up to the deadline to get a real answer. A shorter drain
                    // cuts it, and the client gets a reset connection instead of a status (research D6, B-07).
                    val drain = configuration[MostikConfig.DRAIN_MS]
                    if (drain < deadline + MostikConfig.DRAIN_MARGIN_MS) {
                        add(
                            "MOSTIK_DRAIN_MS ($drain) must be at least MOSTIK_PUBLISH_DEADLINE_MS ($deadline) + " +
                                "${MostikConfig.DRAIN_MARGIN_MS} ms: a request in flight at SIGTERM would be cut " +
                                "before its answer",
                        )
                    }
                    if (configuration[MostikConfig.MAX_RECORD_BYTES] <= 0) {
                        add("MOSTIK_MAX_RECORD_BYTES must be positive")
                    }
                }
            if (problems.isNotEmpty()) return Result.failure(IllegalArgumentException(problems.joinToString("\n")))
            return Result.success(
                MostikSettings(
                    port = configuration[MostikConfig.PORT],
                    bootstrapServers = configuration[MostikConfig.BOOTSTRAP_SERVERS],
                    topics = topics,
                    publishDeadlineMs = configuration[MostikConfig.PUBLISH_DEADLINE_MS],
                    queueWaitMs = configuration[MostikConfig.QUEUE_WAIT_MS],
                    drainMs = configuration[MostikConfig.DRAIN_MS],
                    maxRecordBytes = configuration[MostikConfig.MAX_RECORD_BYTES],
                    observed = configuration[MostikConfig.TRACY_ENDPOINT] != null,
                    producerKeys = producerKeys,
                ),
            )
        }
    }
}
