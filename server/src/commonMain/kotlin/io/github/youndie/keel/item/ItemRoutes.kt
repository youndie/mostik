package io.github.youndie.keel.item

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * `GET /items` and `POST /items` — the whole HTTP surface keel ships.
 *
 * The route renders a body rather than answering a bare status, and that is load-bearing on
 * Kotlin/Native: every rendered byte goes through glibc `iconv`, which is `dlopen`ed, so a status
 * code crosses no charset. A `401` from a static image was once read as a pass for exactly that
 * reason (`docs/research/research-architecture.md` §1.5).
 *
 * The second endpoint in a service is already a feature, and features are `ktor-server-feature`'s:
 * typed `@Resource`, a use case, the layers. keel stops here on purpose.
 */
fun Application.itemRoutes(store: ItemStore) {
    routing {
        get("/items") { call.respond(store.all()) }

        post("/items") {
            val item = call.receive<Item>()
            call.respond(HttpStatusCode.Created, store.add(item))
        }
    }
}
