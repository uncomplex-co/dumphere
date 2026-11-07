package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.ArtifactVisibility
import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.PageContentFormat
import dev.uncomplex.dumphere.application.UserProvisioningService
import dev.uncomplex.dumphere.ports.PublishedArtifactsStore
import dev.uncomplex.dumphere.testing.PostgresTestDatabase
import kotlin.test.assertEquals
import org.junit.jupiter.params.provider.Arguments
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        [
            "spring.security.oauth2.client.registration.google.client-id=test-client",
            "spring.security.oauth2.client.registration.google.client-secret=test-secret",
            "spring.security.oauth2.client.registration.google.scope=openid,email,profile",
            "htmlshare.allowed-email-domain=example.com",
            "spring.ai.mcp.server.protocol=STREAMABLE",
        ],
)
@AutoConfigureMockMvc
abstract class CommentMutationTestSupport {
    @Autowired protected lateinit var mvc: MockMvc
    @Autowired protected lateinit var json: ObjectMapper
    @Autowired protected lateinit var jdbc: JdbcTemplate
    @Autowired protected lateinit var pages: PublishedArtifactsStore
    @Autowired protected lateinit var users: UserProvisioningService

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = PostgresTestDatabase.configure(registry)

        @JvmStatic
        fun invalidComments() =
            listOf(
                Arguments.of("blank body", "   ", 1),
                Arguments.of("oversized body", "x".repeat(10001), 1),
                Arguments.of("unknown version", "Invalid version", 99),
            )

        @JvmStatic
        fun invalidBodies() =
            listOf(
                Arguments.of("blank body", "   "),
                Arguments.of("oversized body", "x".repeat(10001)),
            )
    }

    protected fun publish(visibility: ArtifactVisibility = ArtifactVisibility.INTERNAL): String {
        val page = pages.publish("Discussion", "<p>Original</p>", PageContentFormat.HTML, null)
        requireNotNull(pages.setVisibility(page.id, visibility, null))
        assertEquals(visibility, requireNotNull(pages.readMetadata(page.id)).visibility.visibility)
        return page.id
    }

    protected fun member(
        email: String = "alex@example.com",
        subject: String = "different-team-member",
    ): RequestPostProcessor {
        users.provision(AuthenticatedUser(subject, email, "Alex Morgan"))
        return oidcLogin().idToken {
            it.subject(subject)
                .claim("email", email)
                .claim("name", "Alex Morgan")
                .claim("picture", "https://example.com/avatar.png")
        }
    }

    protected fun revokePublicAccess(id: String) {
        assertEquals(ArtifactVisibility.PUBLIC, requireNotNull(pages.readMetadata(id)).visibility.visibility)
        requireNotNull(pages.setVisibility(id, ArtifactVisibility.INTERNAL, null))
        assertEquals(ArtifactVisibility.INTERNAL, requireNotNull(pages.readMetadata(id)).visibility.visibility)
    }

    protected fun createComment(
        id: String,
        body: String = "Root",
        identity: RequestPostProcessor = member(),
    ): JsonNode =
        json
            .readTree(
                mvc.perform(
                        post("/p/$id/comments")
                            .with(identity)
                            .with(csrf())
                            .contentType("application/json")
                            .content(
                                json.writeValueAsString(
                                    mapOf(
                                        "body" to body,
                                        "version" to 1,
                                        "xpath" to "/html/body/p",
                                        "relativeX" to 0.5,
                                        "relativeY" to 0.5,
                                    )
                                )
                            )
                    )
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
            )
            .also { created -> assertEquals(body, commentRow(created)["body"]) }

    protected fun createReply(
        id: String,
        root: JsonNode,
        body: String = "Reply",
        identity: RequestPostProcessor = member(),
    ): JsonNode =
        json
            .readTree(
                mvc.perform(
                        post("/p/$id/threads/${root.path("threadId").asLong()}/comments")
                            .with(identity)
                            .with(csrf())
                            .contentType("application/json")
                            .content(json.writeValueAsString(mapOf("body" to body, "version" to 1)))
                    )
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
            )
            .also { created -> assertEquals(body, commentRow(created)["body"]) }

    protected fun listedComments(id: String, identity: RequestPostProcessor = member()): JsonNode =
        json.readTree(
            mvc.perform(get("/p/$id/comments").with(identity))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        )

    protected fun commentRow(comment: JsonNode) =
        jdbc.queryForMap("select * from comments where id = ?", comment.path("id").asLong())

    protected fun snapshot(id: String) =
        DiscussionSnapshot(
            page = jdbc.queryForMap("select * from html_pages where id = ?", id),
            versions = jdbc.queryForList("select * from html_page_versions where page_id = ? order by id", id),
            threads = jdbc.queryForList("select * from comment_threads where artifact_id = ? order by id", id),
            comments =
                jdbc.queryForList(
                    """
                    select c.* from comments c join comment_threads t on t.id = c.thread_id
                    where t.artifact_id = ? order by c.id
                    """
                        .trimIndent(),
                    id,
                ),
        )
}

data class DiscussionSnapshot(
    val page: Map<String, Any?>,
    val versions: List<Map<String, Any?>>,
    val threads: List<Map<String, Any?>>,
    val comments: List<Map<String, Any?>>,
)
