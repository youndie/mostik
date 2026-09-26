package io.github.youndie.mostik

import io.github.youndie.kafkakn.ProducerConfig
import io.github.youndie.kafkakn.kafkaProducer
import io.github.youndie.kore.config.Environment
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFails

/**
 * What the start-up does with the producer, through the real kafkakn on each arm — librdkafka on
 * `linuxX64`, the Java client on the JVM — and no broker.
 *
 * The misspelling is refused by kafkakn, not by mostik (research §1.10). This test is the only thing that
 * says the refusal still happens at construction, where the start-up can turn it into an exit, rather
 * than at the first record, where it would be a request's failure.
 */
class ProducerConstructionTest {
    private fun properties(extra: Map<String, String>): Map<String, String> {
        val env =
            Environment.of(
                mapOf("MOSTIK_BOOTSTRAP_SERVERS" to "127.0.0.1:1", "MOSTIK_TOPICS" to "orders") + extra,
            )
        return MostikSettings
            .from(MostikConfig.SCHEMA.read(env), MostikConfig.kafkaPassThrough(env).getOrThrow())
            .getOrThrow()
            .producerProperties()
    }

    @Test
    fun `a misspelt KAFKA key stops the producer at construction`() {
        assertFails("KAFKA_ACSK became `acsk`, which neither arm honours") {
            kafkaProducer(ProducerConfig(properties(mapOf("KAFKA_ACSK" to "all"))))
        }
    }

    /** The positive control of the test above: the same path with the key spelt right constructs, and closes. */
    @Test
    fun `a correctly spelt KAFKA key constructs a producer that closes with nothing to flush`() {
        val producer = kafkaProducer(ProducerConfig(properties(mapOf("KAFKA_ACKS" to "all"))))
        runBlocking { producer.close() }
    }
}
