package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.application.ArtifactVisibility
import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.PageContentFormat
import dev.uncomplex.dumphere.ports.PublishedArtifactsStore
import dev.uncomplex.dumphere.testing.McpTestClient
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters

class ArtifactProfilesMcpIntegrationTests : ArtifactMcpTestSupport() {
    @Autowired private lateinit var pages: PublishedArtifactsStore

    private val creator = AuthenticatedUser("profile-creator", "creator@example.com", "Original Creator")
    private val previous = AuthenticatedUser("profile-previous", "previous@example.com", "Previous Editor")
    private val editor = AuthenticatedUser("profile-editor", "editor@example.com", "Different Editor")

    @Test
    fun `publishing records the creator with no updater and nested internal visibility`() {
        // Arrange
        client(creator).use { client ->
            // Act
            val result =
                client.metadata(
                    client.call(
                        "publish_html",
                        mapOf(
                            "contents" to "<p>Profiles</p>",
                            "format" to "html",
                        ),
                    )
                )

            // Assert
            val id = result.path("id").asString()
            assertEquals(creatorProfile(), result.path("createdBy"))
            assertTrue(result.path("updatedBy").isNull)
            assertTrue(result.path("visibility").isObject)
            assertEquals("internal", result.path("visibility").path("visibility").asString())
            assertTrue(result.path("visibility").path("publicUntil").isNull)
            assertFalse(result.has("publicUntil"))
            assertEquals(mapOf("creator" to "profile-creator", "updater" to null), storedProfiles(id))
            assertEquals("<p>Profiles</p>", contents(id))
        }
    }

    @Test
    fun `metadata reads return creator updater and public visibility without changing the artifact`() {
        // Arrange
        val page = artifactWithPreviousUpdater()
        val shared = requireNotNull(pages.setVisibility(page.id, ArtifactVisibility.PUBLIC, users.provision(previous)))
        val before = jdbc.queryForMap("select * from html_pages where id = ?", page.id)
        client(creator).use { client ->
            // Act
            val result = client.metadata(client.call("get_artifact_metadata", mapOf("id" to page.id)))

            // Assert
            assertEquals(creatorProfile(), result.path("createdBy"))
            assertEquals(
                json.readTree("""{"email":"previous@example.com","name":"Previous Editor"}"""),
                result.path("updatedBy"),
            )
            assertTrue(result.path("visibility").isObject)
            assertEquals("public", result.path("visibility").path("visibility").asString())
            assertEquals(
                shared.visibility.publicUntil,
                Instant.parse(result.path("visibility").path("publicUntil").asString()),
            )
            assertFalse(result.has("publicUntil"))
            assertEquals(before, jdbc.queryForMap("select * from html_pages where id = ?", page.id))
        }
    }

    @Test
    fun `content updates replace the prior updater while preserving the creator`() {
        // Arrange
        val page = artifactWithPreviousUpdater()
        client(editor).use { client ->
            // Act
            val result =
                client.metadata(
                    client.call(
                        "update_html",
                        mapOf(
                            "id" to page.id,
                            "contents" to "<p>Updated</p>",
                        ),
                    )
                )

            // Assert
            assertEquals(creatorProfile(), result.path("createdBy"))
            assertEquals(editorProfile(), result.path("updatedBy"))
            assertEquals(mapOf("creator" to "profile-creator", "updater" to "profile-editor"), storedProfiles(page.id))
            assertEquals("<p>Updated</p>", contents(page.id))
            assertEquals(3, result.path("version").asInt())
            assertEquals(3, storedVersion(page.id))
            assertEquals(3, versionCount(page.id))
        }
    }

    @Test
    fun `sharing records the current updater and persists a public deadline without a content version`() {
        // Arrange
        val page = artifactWithPreviousUpdater()
        client(editor).use { client ->
            // Act
            val result =
                client.metadata(
                    client.call(
                        "set_artifact_visibility",
                        mapOf(
                            "id" to page.id,
                            "visibility" to "public",
                        ),
                    )
                )

            // Assert
            assertEquals(creatorProfile(), result.path("createdBy"))
            assertEquals(editorProfile(), result.path("updatedBy"))
            assertEquals(mapOf("creator" to "profile-creator", "updater" to "profile-editor"), storedProfiles(page.id))
            assertEquals("public", result.path("visibility").path("visibility").asString())
            val until =
                assertNotNull(
                        jdbc.queryForObject(
                            "select public_until from html_pages where id = ?",
                            Timestamp::class.java,
                            page.id,
                        )
                    )
                    .toInstant()
            assertTrue(until.isAfter(Instant.now()))
            assertEquals(until, Instant.parse(result.path("visibility").path("publicUntil").asString()))
            assertEquals(2, storedVersion(page.id))
            assertEquals(2, versionCount(page.id))
            assertEquals("<p>Previously updated</p>", contents(page.id))
        }
    }

    @Test
    fun `custom metadata updates record the current updater and persist the value without a content version`() {
        // Arrange
        val page = artifactWithPreviousUpdater()
        client(editor).use { client ->
            // Act
            val result =
                client.metadata(
                    client.call(
                        "set_artifact_metadata",
                        mapOf(
                            "id" to page.id,
                            "key" to "origin",
                            "value" to "MCP",
                        ),
                    )
                )

            // Assert
            assertEquals(creatorProfile(), result.path("createdBy"))
            assertEquals(editorProfile(), result.path("updatedBy"))
            assertEquals(mapOf("creator" to "profile-creator", "updater" to "profile-editor"), storedProfiles(page.id))
            assertEquals("MCP", result.path("customMetadata").path("origin").asString())
            assertEquals(
                "MCP",
                jdbc.queryForObject(
                    "select custom_metadata->>'origin' from html_pages where id = ?",
                    String::class.java,
                    page.id,
                ),
            )
            assertEquals(2, storedVersion(page.id))
            assertEquals(2, versionCount(page.id))
            assertEquals("<p>Previously updated</p>", contents(page.id))
        }
    }

    private fun artifactWithPreviousUpdater() =
        pages
            .publish(
                "Profiles",
                "<p>Profiles</p>",
                PageContentFormat.HTML,
                users.provision(creator),
            )
            .let { page ->
                requireNotNull(pages.update(page.id, "<p>Previously updated</p>", users.provision(previous))).also {
                    assertEquals(
                        mapOf("creator" to "profile-creator", "updater" to "profile-previous"),
                        storedProfiles(page.id),
                    )
                }
            }

    private fun client(identity: AuthenticatedUser): McpTestClient {
        val now = Instant.now()
        val token =
            jwtEncoder
                .encode(
                    JwtEncoderParameters.from(
                        JwtClaimsSet.builder()
                            .subject(identity.subject)
                            .claim("email", requireNotNull(identity.email))
                            .claim("name", requireNotNull(identity.displayName))
                            .issuedAt(now)
                            .expiresAt(now.plusSeconds(120))
                            .build()
                    )
                )
                .tokenValue
        return McpTestClient(port, token, json).apply { initialize() }
    }

    private fun creatorProfile() = json.readTree("""{"email":"creator@example.com","name":"Original Creator"}""")

    private fun editorProfile() = json.readTree("""{"email":"editor@example.com","name":"Different Editor"}""")

    private fun storedProfiles(id: String) =
        jdbc.queryForMap(
            """
            select creator.subject as creator, updater.subject as updater from html_pages p
            join users creator on creator.id = p.created_by_user_id
            left join users updater on updater.id = p.updated_by_user_id where p.id = ?
            """
                .trimIndent(),
            id,
        )

    private fun storedVersion(id: String) =
        jdbc.queryForObject("select current_version from html_pages where id = ?", Int::class.java, id)

    private fun versionCount(id: String) =
        jdbc.queryForObject("select count(*) from html_page_versions where page_id = ?", Int::class.java, id)
}
