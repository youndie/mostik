package io.github.youndie.mostik.publish

import io.github.youndie.kafkakn.Delivery
import io.github.youndie.kafkakn.ProducerRecord
import io.github.youndie.kafkakn.RecordHeader
import io.github.youndie.kafkakn.RecordNotQueuedException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.TimeSource

/** The header whose value becomes the record's key. Absent means a record with no key. */
const val RECORD_KEY_HEADER: String = "Record-Key"

/** Every header with this prefix becomes a record header named by the rest of its name. */
const val RECORD_HEADER_PREFIX: String = "Record-Header-"

/** Where the broker put the record: the `200` body (endpoint-records). */
@Serializable
class PublishedRecord(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val timestamp: Long,
)

/** Every error this route answers with. [error] is the contract; [detail] is for a person and may change. */
@Serializable
class ErrorBody(
    val error: String,
    val detail: String,
)

/**
 * The `504` body: the record's fate is unknown, and a retry may write it twice.
 *
 * A class of its own rather than defaults on [ErrorBody]: Ktor's `Json` does not encode default values, so a
 * `retrySafe = false` default would silently vanish from every response.
 */
@Serializable
class OutcomeUnknownBody(
    val error: String,
    val detail: String,
    val outcome: String,
    val retrySafe: Boolean,
)

/**
 * `POST /topics/{topic}/records`: one request, one record, and a status that is a statement about the record.
 *
 * **No authentication here, and that is the contract** (research D4): the reverse proxy authenticates, and
 * [topics] is what limits where this service can write. A topic outside it is `404` before the producer is
 * touched, and so is a body over [maxRecordBytes] (`413`).
 *
 * **The body is bytes, never parsed.** It is the record's value exactly as sent (research D1), so nothing on
 * this path reads it as text or JSON. An empty body is an empty value, not a tombstone.
 *
 * **The deadline is split in two, and the split is what keeps both answers true** (research §1.4, B-05):
 *
 * 1. [enqueue] — kafkakn's `enqueue` in production — runs with **no** coroutine timeout. The producer's own
 *    `max.block.ms` bounds it. `RecordNotQueuedException` means the record was never queued, so it is `429`, and
 *    a retry cannot write it twice. A timeout here is the obvious code and the wrong one: on the JVM the client
 *    does not give the thread back while it waits for room, so a cut is honoured late, by which time the record
 *    may be queued (kafkakn B-73).
 * 2. `await()` runs under `withTimeout` for what is left of [deadline]. An expiry is `504 outcome-unknown`: the
 *    record is queued and goes on, and it lands if the broker answers later.
 *
 * Any other failure of step 1 is `502 producer-refused`: the producer refused the record before queueing it, so
 * it was not written, and waiting will not change the answer. Any failure of step 2 is `504`, because a record
 * whose delivery failed may still have been written (research H3, B-06).
 *
 * [timeSource] is the clock the time spent in step 1 is read from. It is a parameter so a test can make step 1
 * take most of the deadline without waiting for it.
 */
fun Route.publishRoutes(
    topics: Set<String>,
    maxRecordBytes: Int,
    deadline: Duration,
    retryAfterSeconds: Int,
    timeSource: TimeSource = TimeSource.Monotonic,
    enqueue: suspend (ProducerRecord) -> Delivery,
) {
    post("/topics/{topic}/records") {
        val topic = call.parameters["topic"].orEmpty()
        if (topic !in topics) {
            call.respondError(HttpStatusCode.NotFound, "topic-not-found", "$topic is not one this service publishes to")
            return@post
        }

        val value = call.readBody(maxRecordBytes)
        if (value == null) {
            call.respondError(
                HttpStatusCode.PayloadTooLarge,
                "record-too-large",
                "the body is over $maxRecordBytes bytes",
            )
            return@post
        }

        val record =
            ProducerRecord(
                topic = topic,
                value = value,
                key = call.request.headers[RECORD_KEY_HEADER]?.encodeToByteArray(),
                headers = call.recordHeaders(),
            )

        val started = timeSource.markNow()
        val delivery =
            try {
                enqueue(record)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (notQueued: RecordNotQueuedException) {
                call.response.headers.append(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
                call.respondError(
                    HttpStatusCode.TooManyRequests,
                    "not-queued",
                    "the record was not queued, and nothing was written: ${notQueued.message}",
                )
                return@post
            } catch (refused: Exception) {
                call.respondError(
                    HttpStatusCode.BadGateway,
                    "producer-refused",
                    "the producer refused the record before queueing it, and nothing was written: ${refused.message}",
                )
                return@post
            }

        val metadata =
            try {
                withTimeout(deadline - started.elapsedNow()) { delivery.await() }
            } catch (expired: TimeoutCancellationException) {
                call.respondOutcomeUnknown("the deadline of $deadline passed after the record was queued")
                return@post
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                call.respondOutcomeUnknown("the delivery failed after the record was queued: ${failure.message}")
                return@post
            }
        call.respond(PublishedRecord(metadata.topic, metadata.partition, metadata.offset, metadata.timestamp))
    }
}

/**
 * The body, or `null` when it is larger than [limit].
 *
 * Checked twice with one constant, as a route that must not parse its body does it: against the declared
 * length before anything is buffered, and against what was actually read, one byte past the limit, so a body
 * that lies about its length is caught by the same rule.
 */
private suspend fun RoutingCall.readBody(limit: Int): ByteArray? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > limit) return null
    val body = receiveChannel().readBuffer(limit.toLong() + 1).readByteArray()
    return body.takeIf { it.size <= limit }
}

/**
 * Every `Record-Header-<name>` as a record header named `<name>`, one per value.
 *
 * The prefix is matched without regard to case, as HTTP header names are. The order is the order the request's
 * headers are iterated in, which is what the endpoint document records as measured.
 */
private fun RoutingCall.recordHeaders(): List<RecordHeader> =
    request.headers.entries().flatMap { (name, values) ->
        if (name.length > RECORD_HEADER_PREFIX.length && name.startsWith(RECORD_HEADER_PREFIX, ignoreCase = true)) {
            val header = name.substring(RECORD_HEADER_PREFIX.length)
            values.map { RecordHeader(header, it.encodeToByteArray()) }
        } else {
            emptyList()
        }
    }

private suspend fun RoutingCall.respondError(
    status: HttpStatusCode,
    error: String,
    detail: String,
) = respond(status, ErrorBody(error, detail))

private suspend fun RoutingCall.respondOutcomeUnknown(detail: String) =
    respond(
        HttpStatusCode.GatewayTimeout,
        OutcomeUnknownBody(error = "outcome-unknown", detail = detail, outcome = "unknown", retrySafe = false),
    )
