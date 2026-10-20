package dev.uncomplex.dumphere.testing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpTestClient(
    private val port: Int,
    private val token: String,
    private val json: ObjectMapper,
) : AutoCloseable {
    private val http = HttpClient.newHttpClient()
    private var session: String? = null
    private var requestId = 0

    fun initialize() {
        val response =
            send(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to ++requestId,
                    "method" to "initialize",
                    "params" to
                        mapOf(
                            "protocolVersion" to "2025-03-26",
                            "capabilities" to emptyMap<String, Any>(),
                            "clientInfo" to mapOf("name" to "sharing-test", "version" to "1"),
                        ),
                ),
            )
        assertEquals(200, response.statusCode(), response.body())
        assertFalse(payload(response).has("error"), response.body())
        assertTrue(payload(response).path("result").has("serverInfo"), response.body())
        session = response.headers().firstValue("Mcp-Session-Id").orElse(null)
        val notification = send(mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized"))
        assertTrue(notification.statusCode() in 200..299, notification.body())
    }

    fun call(
        name: String,
        arguments: Map<String, Any>,
    ): JsonNode {
        val response =
            send(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to ++requestId,
                    "method" to "tools/call",
                    "params" to mapOf("name" to name, "arguments" to arguments),
                ),
            )
        assertEquals(200, response.statusCode(), response.body())
        val message = payload(response)
        assertFalse(message.has("error"), response.body())
        val result = message.path("result")
        assertFalse(result.path("isError").asBoolean(), response.body())
        assertTrue(result.path("content").isArray, response.body())
        return result
    }

    fun metadata(result: JsonNode): JsonNode {
        val structured = result.path("structuredContent")
        if (structured.has("id")) return structured
        return json.readTree(
            result
                .path("content")
                .first()
                .path("text")
                .asText(),
        )
    }

    private fun send(body: Map<String, Any>): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/mcp"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-03-26")
        session?.let { request.header("Mcp-Session-Id", it) }
        return http.send(
            request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun payload(response: HttpResponse<String>): JsonNode =
        json.readTree(
            response
                .body()
                .lineSequence()
                .firstOrNull { it.startsWith("data:") }
                ?.removePrefix("data:")
                ?.trim() ?: response.body(),
        )

    override fun close() = http.close()
}
