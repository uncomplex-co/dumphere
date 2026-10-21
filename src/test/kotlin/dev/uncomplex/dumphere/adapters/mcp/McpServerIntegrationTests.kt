package dev.uncomplex.dumphere.adapters.mcp

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.ai.mcp.server.name=htmlshare",
        "spring.ai.mcp.server.protocol=STREAMABLE",
    ],
)
class McpServerIntegrationTests {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jwtEncoder: JwtEncoder

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Test
    fun authenticatesAndListsToolsAndReturnsToolErrors() {
        val token =
            jwtEncoder
                .encode(
                    JwtEncoderParameters.from(
                        JwtClaimsSet
                            .builder()
                            .subject("mcp-test-user")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build(),
                    ),
                ).tokenValue
        val client = HttpClient.newHttpClient()
        val initialized =
            post(
                client,
                token,
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 1,
                    "method" to "initialize",
                    "params" to
                        mapOf(
                            "protocolVersion" to "2025-03-26",
                            "capabilities" to emptyMap<String, Any>(),
                            "clientInfo" to mapOf("name" to "integration-test", "version" to "1"),
                        ),
                ),
            )
        assertEquals(200, initialized.statusCode(), initialized.body())
        assertEquals(
            "htmlshare",
            payload(initialized)
                .path("result")
                .path("serverInfo")
                .path("name")
                .asString(),
        )
        val session = initialized.headers().firstValue("Mcp-Session-Id").orElse(null)
        val notification =
            post(
                client,
                token,
                mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized"),
                session,
            )
        assertTrue(notification.statusCode() in 200..299, notification.body())

        val listed = post(client, token, mapOf("jsonrpc" to "2.0", "id" to 2, "method" to "tools/list"), session)
        assertEquals(200, listed.statusCode(), listed.body())
        val names =
            payload(listed)
                .path("result")
                .required("tools")
                .asArray()
                .values()
                .map { it.path("name").asString() }
                .toSet()
        assertEquals(
            setOf(
                "publish_html",
                "read_file_contents",
                "update_html",
                "edit_file_contents",
                "enable_live_reload",
                "get_artifact_metadata",
                "set_artifact_visibility",
            ),
            names,
        )

        val called =
            post(
                client,
                token,
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 3,
                    "method" to "tools/call",
                    "params" to mapOf("name" to "read_file_contents", "arguments" to mapOf("id" to "invalid!")),
                ),
                session,
            )
        assertEquals(200, called.statusCode(), called.body())
        assertTrue(payload(called).path("result").path("isError").asBoolean(), called.body())
        assertTrue(called.body().contains("page not found"), called.body())
    }

    private fun post(
        client: HttpClient,
        token: String,
        body: Map<String, Any>,
        session: String? = null,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/mcp"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-03-26")
        session?.let { builder.header("Mcp-Session-Id", it) }

        return client.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun payload(response: HttpResponse<String>): JsonNode =
        mapper.readTree(
            response
                .body()
                .lineSequence()
                .firstOrNull { it.startsWith("data:") }
                ?.removePrefix("data:")
                ?.trim() ?: response.body(),
        )
}
