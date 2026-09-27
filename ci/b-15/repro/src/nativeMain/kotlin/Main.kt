import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay

// B-15: read the body, wait 10 ms, answer a small JSON object. That is the shape of mostik's publish with its
// Kafka send replaced by a 10 ms fake, which still lost an answer in about 60 000 on linuxX64.
fun main() {
    embeddedServer(CIO, port = 18108, host = "0.0.0.0") {
        routing {
            post("/records") {
                call.receiveText()
                delay(10)
                call.respondText("""{"topic":"orders","partition":0,"offset":0,"timestamp":0}""", ContentType.Application.Json)
            }
        }
    }.start(wait = true)
}
