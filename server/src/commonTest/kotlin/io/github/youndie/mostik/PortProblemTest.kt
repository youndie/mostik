package io.github.youndie.mostik

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** B-10: a busy port is one sentence naming MOSTIK_PORT, and a free one is no problem. */
class PortProblemTest {
    @Test
    fun `a port another socket holds is named with MOSTIK_PORT`() =
        runBlocking {
            SelectorManager().use { selector ->
                aSocket(selector).tcp().bind("0.0.0.0", 0).use { holder ->
                    val port = (holder.localAddress as InetSocketAddress).port

                    assertContains(portProblem(port).orEmpty(), "MOSTIK_PORT ($port) cannot be listened on")
                }
            }
        }

    /** The positive control of the test above: the same port, released, binds. */
    @Test
    fun `a free port is no problem`() =
        runBlocking {
            val port =
                SelectorManager().use { selector ->
                    aSocket(selector).tcp().bind("0.0.0.0", 0).use { (it.localAddress as InetSocketAddress).port }
                }

            assertEquals(null, portProblem(port))
        }
}
