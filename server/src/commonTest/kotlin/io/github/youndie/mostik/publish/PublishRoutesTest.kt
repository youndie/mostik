package io.github.youndie.mostik.publish

import io.github.youndie.kafkakn.Delivery
import io.github.youndie.kafkakn.ProducerRecord
import io.github.youndie.kafkakn.RecordMetadata
import io.github.youndie.kafkakn.RecordNotQueuedException
import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.mostik.MostikSettings
import io.github.youndie.mostik.mostikModule
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * The route's own decisions, without a broker: what is refused before sending, how a request becomes a record,
 * and how the two steps of the deadline become statuses. What the broker does with a record is not asked here;
 * `ci/b-03/run.sh` and `ci/b-05/run.sh` read the topic.
 *
 * The enqueue function records what it was handed. By default its delivery answers at once with fixed metadata,
 * and each deadline test replaces it with the one behaviour it is about.
 */
class PublishRoutesTest {
    private val sent = mutableListOf<ProducerRecord>()

    private val settings =
        MostikSettings(
            port = 0,
            bootstrapServers = "unused",
            topics = setOf("orders", "payments"),
            publishDeadlineMs = 5_000,
            queueWaitMs = 1_000,
            drainMs = 15_000,
            maxRecordBytes = 64,
            observed = false,
            producerKeys = emptyMap(),
        )

    private fun ApplicationTestBuilder.mostik(enqueue: suspend (ProducerRecord) -> Delivery = { acknowledged(it) }) =
        application { mostikModule(StartupGate(), ReadinessGate(), LivenessGate(), settings, enqueue) }

    /** The route alone, with a deadline and a clock of the test's choosing, and the same JSON `mostikModule` installs. */
    private fun ApplicationTestBuilder.route(
        deadline: Duration,
        timeSource: TimeSource = TimeSource.Monotonic,
        enqueue: suspend (ProducerRecord) -> Delivery,
    ) = application {
        install(ContentNegotiation) { json() }
        routing { publishRoutes(setOf("orders"), 64, deadline, retryAfterSeconds = 1, timeSource, enqueue) }
    }

    private fun acknowledged(record: ProducerRecord): Delivery {
        sent += record
        return delivery { RecordMetadata(record.topic, partition = 2, offset = 41, timestamp = 1_790_000_000_000) }
    }

    private fun delivery(answer: suspend () -> RecordMetadata): Delivery =
        object : Delivery {
            override suspend fun await(): RecordMetadata = answer()
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

    /** Step 1 of the deadline: never queued is `429`, with `Retry-After` from the queue wait. */
    @Test
    fun `a record not queued is 429 with Retry-After`() =
        testApplication {
            mostik { throw RecordNotQueuedException("orders: not queued: the queue stayed full") }

            val response = client.post("/topics/orders/records") { setBody("x") }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("1", response.headers[HttpHeaders.RetryAfter])
            assertTrue(""""error":"not-queued"""" in response.bodyAsText(), response.bodyAsText())
        }

    /** Step 1, any other refusal: not queued, and waiting will not help, so not `429`. */
    @Test
    fun `any other failure to enqueue is 502 producer-refused`() =
        testApplication {
            mostik { throw IllegalStateException("the producer is closed") }

            val response = client.post("/topics/orders/records") { setBody("x") }

            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertTrue(""""error":"producer-refused"""" in response.bodyAsText(), response.bodyAsText())
            assertEquals(null, response.headers[HttpHeaders.RetryAfter])
        }

    /** Step 2: queued, and the broker never answers within the deadline. */
    @Test
    fun `a delivery that does not answer within the deadline is 504 outcome-unknown`() =
        testApplication {
            route(deadline = 200.milliseconds) { delivery { awaitCancellation() } }

            val response = client.post("/topics/orders/records") { setBody("x") }

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            val body = response.bodyAsText()
            assertTrue(""""error":"outcome-unknown"""" in body, body)
            assertTrue(""""outcome":"unknown"""" in body && """"retrySafe":false""" in body, body)
        }

    /**
     * Step 2 gets what is LEFT of the deadline, not all of it.
     *
     * The clock says step 1 took 900 ms of a 1 000 ms deadline, and the delivery answers after 300 ms of real time.
     * With the remainder (100 ms) that is a `504`; a route that restarted the deadline at step 2 would answer `200`.
     */
    @Test
    fun `the time spent queueing is taken off the deadline`() =
        testApplication {
            val clock = TestTimeSource()
            route(deadline = 1_000.milliseconds, timeSource = clock) {
                clock += 900.milliseconds
                delivery {
                    delay(300.milliseconds)
                    RecordMetadata("orders", partition = 0, offset = 0, timestamp = 0)
                }
            }

            val response = client.post("/topics/orders/records") { setBody("x") }

            assertEquals(HttpStatusCode.GatewayTimeout, response.status, response.bodyAsText())
        }

    /** Research D2: a delivery that fails after queueing is "unknown", never a 500. */
    @Test
    fun `a delivery that fails after queueing is 504 and says a retry is not safe`() =
        testApplication {
            mostik { delivery { error("the broker said no") } }

            val response = client.post("/topics/orders/records") { setBody("x") }

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            val body = response.bodyAsText()
            assertTrue(""""outcome":"unknown"""" in body && """"retrySafe":false""" in body, body)
        }

    private companion object {
        /** The sample order of the technical brief §5a. */
        const val ORDER = """{"orderId":1042,"amount":"19.90","currency":"EUR"}"""
    }
}
