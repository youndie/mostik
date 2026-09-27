package io.github.youndie.mostik

import io.github.youndie.kore.config.ConfigurationException
import io.github.youndie.kore.config.Environment
import io.github.youndie.kore.config.Origin
import io.github.youndie.kore.config.printConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The configuration refuses before it serves, and says everything that is wrong.
 *
 * These run on the JVM and on `linuxX64` from one source. The schema is kore's and common code; the
 * `KAFKA_*` pass-through is mostik's, and depends on the environment being listable.
 */
class MostikConfigTest {
    private val complete =
        mapOf(
            "MOSTIK_BOOTSTRAP_SERVERS" to "127.0.0.1:9092",
            "MOSTIK_TOPICS" to "orders,payments",
        )

    private fun settings(environment: Map<String, String>): MostikSettings {
        val env = Environment.of(environment)
        val configuration = MostikConfig.SCHEMA.read(env)
        return MostikSettings.from(configuration, MostikConfig.kafkaPassThrough(env).getOrThrow()).getOrThrow()
    }

    /** A bridge with nowhere to publish and nothing it may publish to does not start. */
    @Test
    fun `nothing set at all names both required keys at once`() {
        val failure =
            assertFailsWith<ConfigurationException> { MostikConfig.SCHEMA.read(Environment.of(emptyMap())) }

        assertEquals(
            setOf("MOSTIK_BOOTSTRAP_SERVERS", "MOSTIK_TOPICS"),
            failure.problems.map { it.variable }.toSet(),
            "both missing keys have to arrive together, not one restart each",
        )
    }

    @Test
    fun `the required keys alone start it and every default is reported as a default`() {
        val configuration = MostikConfig.SCHEMA.read(Environment.of(complete))

        assertEquals(8080, configuration[MostikConfig.PORT])
        assertEquals(5_000, configuration[MostikConfig.PUBLISH_DEADLINE_MS])
        assertEquals(1_048_576, configuration[MostikConfig.MAX_RECORD_BYTES])
        val defaulted =
            configuration
                .values()
                .filter { it.origin == Origin.DEFAULT }
                .map { it.variable }
                .toSet()
        assertTrue("MOSTIK_PUBLISH_DEADLINE_MS" in defaulted && "MOSTIK_PORT" in defaulted, "defaulted: $defaulted")
    }

    /** B-01's third acceptance criterion: `--print-config` names every key an operator has to know about. */
    @Test
    fun `print-config lists the keys an operator sets`() {
        val printed = MostikConfig.SCHEMA.printConfig(Environment.of(complete)).text

        listOf(
            "MOSTIK_BOOTSTRAP_SERVERS",
            "MOSTIK_TOPICS",
            "MOSTIK_PUBLISH_DEADLINE_MS",
            "MOSTIK_MAX_RECORD_BYTES",
        ).forEach { assertContains(printed, it) }
    }

    @Test
    fun `the allowlist is split and trimmed and has no empty entries`() {
        val topics = settings(complete + ("MOSTIK_TOPICS" to " orders, ,payments,")).topics

        assertEquals(setOf("orders", "payments"), topics)
    }

    /** `MOSTIK_TOPICS=","` passes kore, which only sees a set value, and would make every publish a 404. */
    @Test
    fun `an allowlist that names no topic is refused`() {
        val env = Environment.of(complete + ("MOSTIK_TOPICS" to " , "))
        val refusal = MostikSettings.from(MostikConfig.SCHEMA.read(env), emptyMap()).exceptionOrNull()

        assertContains(refusal?.message.orEmpty(), "MOSTIK_TOPICS names no topic")
    }

    @Test
    fun `a non-positive deadline is refused`() {
        val env = Environment.of(complete + ("MOSTIK_PUBLISH_DEADLINE_MS" to "0"))
        val refusal = MostikSettings.from(MostikConfig.SCHEMA.read(env), emptyMap()).exceptionOrNull()

        assertContains(refusal?.message.orEmpty(), "MOSTIK_PUBLISH_DEADLINE_MS must be positive")
    }

    /** B-01's second half of the pass-through criterion: a `KAFKA_*` variable reaches the producer as its dotted key. */
    @Test
    fun `a KAFKA variable becomes the producer key it names`() {
        val settings =
            settings(
                complete +
                    mapOf(
                        "KAFKA_ACKS" to "all",
                        "KAFKA_SSL_CA_LOCATION" to "/etc/ca.pem",
                        "NOT_KAFKA_ACKS" to "1",
                    ),
            )

        assertEquals(
            mapOf(
                "acks" to "all",
                "ssl.ca.location" to "/etc/ca.pem",
                "bootstrap.servers" to "127.0.0.1:9092",
                "max.block.ms" to "1000",
            ),
            settings.producerProperties(),
        )
    }

    /** One value, one source: `bootstrap.servers` is `MOSTIK_BOOTSTRAP_SERVERS`, and a second spelling is refused. */
    @Test
    fun `a KAFKA variable for a key mostik owns is refused and names the owner`() {
        val refusal =
            MostikConfig.kafkaPassThrough(Environment.of(complete + ("KAFKA_BOOTSTRAP_SERVERS" to "10.0.0.1:9092")))

        assertContains(refusal.exceptionOrNull()?.message.orEmpty(), "comes from MOSTIK_BOOTSTRAP_SERVERS")
    }

    /**
     * Where the environment cannot be listed, the pass-through refuses rather than passing nothing.
     *
     * mostik ships no such target; the test is what keeps "nothing found" from being read as "nothing set".
     */
    @Test
    fun `an environment that cannot be listed refuses the pass-through`() {
        val refusal =
            MostikConfig.kafkaPassThrough(
                Environment.unlistable(complete + ("KAFKA_ACKS" to "all"), reason = "this target cannot list it"),
            )

        assertContains(refusal.exceptionOrNull()?.message.orEmpty(), "cannot be read here")
    }

    /** A secret is masked by the declaration, and producer keys are listed by name only. */
    @Test
    fun `nothing secret is rendered`() {
        val env =
            complete +
                mapOf(
                    "MOSTIK_TRACY_ENDPOINT" to "https://tracy.example",
                    "MOSTIK_TRACY_KEY" to "the-tracy-secret",
                    "KAFKA_SASL_PASSWORD" to "the-kafka-secret",
                )
        val configuration = MostikConfig.SCHEMA.read(Environment.of(env))
        val rendered = configuration.values().single { it.variable == "MOSTIK_TRACY_KEY" }.rendered
        val described = settings(env).describe()

        assertTrue("the-tracy-secret" !in rendered, "the secret was rendered verbatim: $rendered")
        assertTrue("the-kafka-secret" !in described, "a producer key's value was described: $described")
        assertContains(described, "sasl.password")
    }

    /** A misspelled variable under the prefix is refused, with the declared name it is probably a misspelling of. */
    @Test
    fun `an unknown variable under the prefix is refused and the near miss is named`() {
        val failure =
            assertFailsWith<ConfigurationException> {
                MostikConfig.SCHEMA.read(Environment.of(complete + ("MOSTIK_TOPIC" to "orders")))
            }

        val problem = failure.problems.single()
        assertEquals("MOSTIK_TOPIC", problem.variable)
        assertContains(
            problem.message,
            "MOSTIK_TOPICS",
            message = "the declared name it is a misspelling of is not named",
        )
    }

    /** B-05: the queue wait bounds step 1, and nothing is left for step 2 unless it is shorter than the deadline. */
    @Test
    fun `a queue wait as long as the deadline is refused and names both keys`() {
        val env =
            Environment.of(complete + mapOf("MOSTIK_QUEUE_WAIT_MS" to "5000", "MOSTIK_PUBLISH_DEADLINE_MS" to "5000"))
        val refusal =
            MostikSettings
                .from(MostikConfig.SCHEMA.read(env), emptyMap())
                .exceptionOrNull()
                ?.message
                .orEmpty()

        assertContains(refusal, "MOSTIK_QUEUE_WAIT_MS (5000) must be shorter than MOSTIK_PUBLISH_DEADLINE_MS (5000)")
    }

    /** B-05: `max.block.ms` has one source, `MOSTIK_QUEUE_WAIT_MS`, and a second spelling stops the start-up. */
    @Test
    fun `KAFKA_MAX_BLOCK_MS is refused and names MOSTIK_QUEUE_WAIT_MS`() {
        val refusal = MostikConfig.kafkaPassThrough(Environment.of(complete + ("KAFKA_MAX_BLOCK_MS" to "60000")))

        assertContains(refusal.exceptionOrNull()?.message.orEmpty(), "max.block.ms comes from MOSTIK_QUEUE_WAIT_MS")
    }

    /** B-07: the drain has to outlast the deadline, or a request in flight at SIGTERM loses its answer. */
    @Test
    fun `a drain shorter than the deadline plus its margin is refused and names both keys`() {
        val env =
            Environment.of(complete + mapOf("MOSTIK_DRAIN_MS" to "3000", "MOSTIK_PUBLISH_DEADLINE_MS" to "5000"))
        val refusal =
            MostikSettings
                .from(MostikConfig.SCHEMA.read(env), emptyMap())
                .exceptionOrNull()
                ?.message
                .orEmpty()

        assertContains(refusal, "MOSTIK_DRAIN_MS (3000) must be at least MOSTIK_PUBLISH_DEADLINE_MS (5000) + 1000 ms")
    }

    /** B-07's other half: kore's default drain with the default deadline starts, and so does the margin exactly. */
    @Test
    fun `the default drain and a drain of exactly deadline plus margin both start`() {
        assertEquals(15_000, settings(complete).drainMs)
        assertEquals(6_000, settings(complete + mapOf("MOSTIK_DRAIN_MS" to "6000")).drainMs)
    }
}
