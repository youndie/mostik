package io.github.youndie.mostik

import io.github.youndie.kafkakn.ProducerConfig
import io.github.youndie.kafkakn.ProducerRecord
import io.github.youndie.kafkakn.RecordNotQueuedException
import io.github.youndie.kafkakn.kafkaProducer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The kafkakn promise mostik's `429` rests on, held on both arms and with no broker (B-04).
 *
 * When there is no metadata for the topic within `max.block.ms`, `enqueue` throws [RecordNotQueuedException]
 * and the record was never queued. Before kafkakn B-76 the native arm queued such a record at 0 ms, and `close`
 * then waited out `message.timeout.ms`, 300 s (B-04, iteration 1). This test is the one that found that, kept
 * so that a kafkakn version pinned later cannot quietly bring it back.
 *
 * The queue-full half of the promise needs the broker fixture and is B-05's.
 */
class EnqueueContractTest {
    @Test
    fun `with no metadata within max_block_ms enqueue refuses and close has nothing to flush`() {
        val producer =
            kafkaProducer(
                ProducerConfig(
                    "bootstrap.servers" to "127.0.0.1:1",
                    "max.block.ms" to MAX_BLOCK_MS.toString(),
                ),
            )
        val started = TimeSource.Monotonic.markNow()
        runBlocking {
            // An outer bound, so that a regression fails here instead of hanging the suite.
            withTimeout(OUTER_BOUND_MS) {
                assertFailsWith<RecordNotQueuedException> {
                    producer.enqueue(ProducerRecord(topic = "orders", value = "v".encodeToByteArray())).await()
                }
            }
        }
        val refusedAfter = started.elapsedNow().inWholeMilliseconds
        assertTrue(refusedAfter >= MAX_BLOCK_MS, "refused after ${refusedAfter}ms, before max.block.ms")

        val closing = TimeSource.Monotonic.markNow()
        runBlocking { withTimeout(OUTER_BOUND_MS) { producer.close() } }
        val closedAfter = closing.elapsedNow().inWholeMilliseconds
        assertTrue(closedAfter < CLOSE_BOUND_MS, "close took ${closedAfter}ms: something was queued after all")
    }

    private companion object {
        const val MAX_BLOCK_MS = 1_000L
        const val OUTER_BOUND_MS = 15_000L

        /** Measured by kafkakn B-76 at 0 ms (native) and 7 ms (JVM); before it, 300 200 ms on native. */
        const val CLOSE_BOUND_MS = 5_000L
    }
}
