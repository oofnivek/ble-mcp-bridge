package dev.blemcp.bridge

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest

const val MCP_PORT = 8765

fun startMcpServer(protocol: McpProtocol, token: String): ApplicationEngine =
    embeddedServer(CIO, port = MCP_PORT, host = "0.0.0.0") {
        routing {
            get("/health") { call.respondText("ok") }

            post("/mcp") {
                if (!call.isAuthorized(token)) return@post

                val response = protocol.handle(call.receiveText())
                val status = HttpStatusCode.fromValue(response.status)
                if (response.body == null) {
                    call.respond(status)
                } else {
                    call.respondText(response.body, ContentType.Application.Json, status)
                }
            }

            // This server does not offer an SSE stream or sessions, which the MCP spec signals with 405.
            get("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed) }
            delete("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed) }
        }
    }.start(wait = false)

/** Claude Code sends no Origin header, so rejecting one also blocks requests from web pages. */
private suspend fun ApplicationCall.isAuthorized(token: String): Boolean {
    if (request.header(HttpHeaders.Origin) != null) {
        respond(HttpStatusCode.Forbidden)
        return false
    }
    val supplied = request.header(HttpHeaders.Authorization).orEmpty().toByteArray()
    val expected = "Bearer $token".toByteArray()
    if (!MessageDigest.isEqual(supplied, expected)) {
        respond(HttpStatusCode.Unauthorized)
        return false
    }
    return true
}
