package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.testing.McpTestClient
import dev.uncomplex.dumphere.testing.PostgresTestDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.security.oauth2.client.registration.google.client-id=test-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-secret",
        "spring.ai.mcp.server.name=dumphere",
        "spring.ai.mcp.server.protocol=STREAMABLE",
        "htmlshare.allowed-email-domain=example.com",
    ],
)
class ArtifactMcpIntegrationTests {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jwtEncoder: JwtEncoder

    @Autowired private lateinit var json: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = PostgresTestDatabase.configure(registry)
    }

    @Test
    fun agentsPublishInspectShareEditAndRevokeWithoutRenewingAccess() {
        val now = Instant.now()
        val token =
            jwtEncoder
                .encode(
                    JwtEncoderParameters.from(
                        JwtClaimsSet
                            .builder()
                            .subject("sharing-agent-user")
                            .claim("email", "agent@example.com")
                            .issuedAt(now)
                            .expiresAt(now.plusSeconds(120))
                            .build(),
                    ),
                ).tokenValue

        McpTestClient(port, token, json).use { client ->
            client.initialize()
            val published =
                client.metadata(
                    client.call(
                        "publish_html",
                        mapOf(
                            "contents" to "# First",
                            "format" to "markdown",
                            "file_name" to "agent-notes.md",
                        ),
                    ),
                )
            val id = published.path("id").asString()
            assertTrue(id.isNotBlank())
            assertEquals("internal", published.path("visibility").asString())
            assertNull(deadline(id))
            assertEquals("# First", contents(id))

            val shared = client.metadata(client.call("set_artifact_visibility", mapOf("id" to id, "visibility" to "public")))
            assertEquals("public", shared.path("visibility").asString())
            val until = assertNotNull(deadline(id))
            assertEquals(until, Instant.parse(shared.path("publicUntil").asString()))
            val inspected = client.metadata(client.call("get_artifact_metadata", mapOf("id" to id)))
            assertEquals("public", inspected.path("visibility").asString())
            assertEquals(published.path("url").asString(), inspected.path("url").asString())
            assertEquals(until, deadline(id))
            client.call("set_artifact_visibility", mapOf("id" to id, "visibility" to "public"))
            assertEquals(until, deadline(id))

            val updated = client.metadata(client.call("update_html", mapOf("id" to id, "contents" to "# Second")))
            assertEquals(2, updated.path("version").asInt())
            assertEquals("# Second", contents(id))
            assertEquals(until, deadline(id))
            client.call("edit_file_contents", mapOf("id" to id, "oldString" to "Second", "newString" to "Third"))
            assertEquals("# Third", contents(id))
            assertEquals(until, deadline(id))
            val read = client.call("read_file_contents", mapOf("id" to id))
            assertContains(read.toString(), "# Third")
            val live = client.metadata(client.call("enable_live_reload", mapOf("id" to id, "live" to true)))
            assertEquals("public", live.path("visibility").asString())
            assertEquals(true, jdbc.queryForObject("select is_live from html_pages where id = ?", Boolean::class.java, id))
            assertEquals(until, deadline(id))

            jdbc.update("update html_pages set public_until = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(1)), id)
            val expired = client.metadata(client.call("get_artifact_metadata", mapOf("id" to id)))
            assertEquals("internal", expired.path("visibility").asString())
            val reshared = client.metadata(client.call("set_artifact_visibility", mapOf("id" to id, "visibility" to "public")))
            assertEquals("public", reshared.path("visibility").asString())
            assertTrue(assertNotNull(deadline(id)).isAfter(until))
            val revoked = client.metadata(client.call("set_artifact_visibility", mapOf("id" to id, "visibility" to "internal")))
            assertEquals("internal", revoked.path("visibility").asString())
            assertNull(deadline(id))
            assertEquals(3, jdbc.queryForObject("select count(*) from html_page_versions where page_id = ?", Int::class.java, id))
            assertEquals(
                "sharing-agent-user",
                jdbc.queryForObject(
                    "select u.subject from users u join html_pages p on p.updated_by_user_id = u.id where p.id = ?",
                    String::class.java,
                    id,
                ),
            )
        }
    }

    private fun deadline(id: String) =
        jdbc.queryForObject("select public_until from html_pages where id = ?", Timestamp::class.java, id)?.toInstant()

    private fun contents(id: String) =
        jdbc.queryForObject(
            "select v.html from html_page_versions v join html_pages p on p.id = v.page_id and p.current_version = v.version where p.id = ?",
            String::class.java,
            id,
        )
}
