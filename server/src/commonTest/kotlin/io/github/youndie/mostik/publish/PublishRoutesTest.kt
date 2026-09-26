package io.github.youndie.mostik.publish

import io.github.youndie.kafkakn.ProducerRecord
import io.github.youndie.kafkakn.RecordMetadata
import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.mostik.MostikSettings
import io.github.youndie.mostik.mostikModule
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The route's own decisions, without a broker: what is refused before sending, and how a request becomes a
 * record. What the broker does with the record is not asked here; B-03's end-to-end run reads the topic.
 *
 * The send function records what it was handed and answers with fixed metadata. It is kinder than production in
 * exactly one way — it never fails or waits — so the test that it throws is written separately.
 */
class PublishRoutesTest {
    private val sent = mutableListOf<ProducerRecord>()

    private val settings =
        MostikSettings(
            port = 0,
            bootstrapServers = "unused",
            topics = setOf("orders", "payments"),
            publishDeadlineMs = 5_000,
            maxRecordBytes = 64,
            observed = false,
            producerKeys = emptyMap(),
        )

    private fun ApplicationTestBuilder.mostik(send: suspend (ProducerRecord) -> RecordMetadata = { acknowledge(it) }) =
        application { mostikModule(StartupGate(), ReadinessGate(), LivenessGate(), settings, send) }

    private fun acknowledge(record: ProducerRecord): RecordMetadata {
        sent += record
        return RecordMetadata(topic = record.topic, partition = 2, offset = 41, timestamp = 1_790_000_000_000)
    }

    @Test
    fun `a record is sent as the body key and headers and the answer is where it landed`() =
        testApplication {
            mostik()

            val response =
                client.post("/topics/orders/records") {
                    header("Record-Key", "order-1042")
                    header("Record-Header-trace-id", "7f3a9c")
                    setBody(ORDER)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                """{"topic":"orders","partition":2,"offset":41,"timestamp":1790000000000}""",
                response.bodyAsText(),
            )
            val record = sent.single()
            assertEquals("orders", record.topic)
            assertContentEquals(ORDER.encodeToByteArray(), record.value)
            assertContentEquals("order-1042".encodeToByteArray(), record.key)
            assertEquals(
                listOf("trace-id" to "7f3a9c"),
                record.headers.map { it.name.lowercase() to it.value?.decodeToString() },
            )
        }

    @Test
    fun `no Record-Key means a record with no key and an empty body is an empty value`() =
        testApplication {
            mostik()

            val response = client.post("/topics/payments/records")

            assertEquals(HttpStatusCode.OK, response.status)
            val record = sent.single()
            assertEquals(null, record.key)
            assertContentEquals(ByteArray(0), record.value, "an empty body is an empty value, not a tombstone")
        }

    @Test
    fun `a topic outside the allowlist is 404 and nothing is sent`() =
        testApplication {
            mostik()

            val response = client.post("/topics/audit/records") { setBody("x") }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(""""error":"topic-not-found"""" in response.bodyAsText(), response.bodyAsText())
            assertTrue(sent.isEmpty(), "the producer was handed a record for a topic outside the allowlist")
        }

    @Test
    fun `a body over the limit is 413 and nothing is sent`() =
        testApplication {
            mostik()

            val response = client.post("/topics/orders/records") { setBody("x".repeat(65)) }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(""""error":"record-too-large"""" in response.bodyAsText(), response.bodyAsText())
            assertTrue(sent.isEmpty(), "the producer was handed a record over the size limit")
        }

    /** The limit itself is allowed: the boundary is "over", not "at". */
    @Test
    fun `a body exactly at the limit is sent`() =
        testApplication {
            mostik()

            val response = client.post("/topics/orders/records") { setBody("x".repeat(64)) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(64, sent.single().value?.size)
        }

    /** Research D2: a send that throws is "unknown", never a 500, and the body says a retry may write twice. */
    @Test
    fun `a send that throws is 504 outcome-unknown and says a retry is not safe`() =
        testApplication {
            mostik { error("the broker said no") }

            val response = client.post("/topics/orders/records") { setBody("x") }

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            val body = response.bodyAsText()
            assertTrue(""""error":"outcome-unknown"""" in body, body)
            assertTrue(""""outcome":"unknown"""" in body && """"retrySafe":false""" in body, body)
        }

    private companion object {
        /** The sample order of the technical brief §5a. */
        const val ORDER = """{"orderId":1042,"amount":"19.90","currency":"EUR"}"""
    }
}
