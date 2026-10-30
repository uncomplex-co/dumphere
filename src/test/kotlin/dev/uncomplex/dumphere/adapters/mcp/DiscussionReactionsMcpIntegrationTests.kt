package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.testing.McpTestClient
import dev.uncomplex.dumphere.testing.PostgresTestDatabase
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
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
@AutoConfigureMockMvc
class DiscussionReactionsMcpIntegrationTests {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var jwtEncoder: JwtEncoder
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mvc: MockMvc

    companion object {
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = PostgresTestDatabase.configure(registry)
    }

    private fun token(name: String) = jwtEncoder.encode(JwtEncoderParameters.from(
        JwtClaimsSet.builder().subject("mcp-reactions-$name").claim("email", "$name@example.com")
            .claim("name", name).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build(),
    )).tokenValue

    private fun client(name: String) = McpTestClient(port, token(name), json).also { it.initialize() }

    private fun publish(client: McpTestClient) = client.metadata(client.call("publish_html", mapOf("contents" to "# Reactions", "format" to "markdown")))
        .path("id").asString()

    private data class Discussion(val id: String, val thread: Long, val root: Long) {
        fun arguments(emoji: String = "👍") = mapOf("id" to id, "comment_id" to root, "emoji" to emoji)
    }

    private fun discussion(client: McpTestClient): Discussion {
        val id = publish(client)
        val root = client.metadata(client.call("start_discussion_thread", mapOf("id" to id, "body" to "Review")))
        return Discussion(id, root.path("threadId").asLong(), root.path("id").asLong())
    }

    private fun comments(client: McpTestClient, discussion: Discussion) = client.metadata(client.call("list_thread_comments",
        mapOf("id" to discussion.id, "thread_id" to discussion.thread)))

    private fun count(comment: Long) = jdbc.queryForObject("select count(*) from comment_reactions where comment_id = ?", Int::class.java, comment)

    @Test
    fun `adding through MCP stores the authenticated author and appears in both discussion reads`() {
        client("Alice").use { client ->
            val discussion = discussion(client)

            val result = client.metadata(client.call("add_comment_reaction", discussion.arguments()))

            val expected = json.readTree("""[{"emoji":"👍","author":"Alice"}]""")
            assertEquals(discussion.root, result.path("commentId").asLong())
            assertEquals(expected, result.path("reactions"))
            assertEquals(expected, comments(client, discussion)[0].path("reactions"))
            assertEquals(expected, client.metadata(client.call("list_discussion_threads", mapOf("id" to discussion.id)))[0].path("reactions"))
            assertEquals(1, count(discussion.root))
        }
    }

    @Test
    fun `MCP can react to replies without attaching the reaction to the root`() {
        client("Alice").use { client ->
            val discussion = discussion(client)
            val reply = client.metadata(client.call("reply_to_thread", mapOf("id" to discussion.id, "thread_id" to discussion.thread, "body" to "Reply")))
            val replyId = reply.path("id").asLong()

            val result = client.metadata(client.call("add_comment_reaction", mapOf("id" to discussion.id, "comment_id" to replyId, "emoji" to "🎉")))

            val expected = json.readTree("""[{"emoji":"🎉","author":"Alice"}]""")
            assertEquals(expected, result.path("reactions"))
            val records = comments(client, discussion)
            assertTrue(records[0].path("reactions").isEmpty)
            assertEquals(expected, records[1].path("reactions"))
            assertEquals(1, count(replyId))
        }
    }

    @Test
    fun `adding the same MCP reaction twice is idempotent`() {
        client("Alice").use { client ->
            val discussion = discussion(client)
            client.call("add_comment_reaction", discussion.arguments())

            val result = client.metadata(client.call("add_comment_reaction", discussion.arguments()))

            assertEquals(json.readTree("""[{"emoji":"👍","author":"Alice"}]"""), result.path("reactions"))
            assertEquals(1, comments(client, discussion)[0].path("reactions").size())
            assertEquals(1, count(discussion.root))
        }
    }

    @Test
    fun `MCP removal affects only the caller's vote`() {
        client("Alice").use { alice ->
            client("Bob").use { bob ->
                val discussion = discussion(alice)
                alice.call("add_comment_reaction", discussion.arguments())
                bob.call("add_comment_reaction", discussion.arguments())

                val result = alice.metadata(alice.call("remove_comment_reaction", discussion.arguments()))

                val expected = json.readTree("""[{"emoji":"👍","author":"Bob"}]""")
                assertEquals(expected, result.path("reactions"))
                assertEquals(expected, comments(bob, discussion)[0].path("reactions"))
                assertEquals(1, count(discussion.root))
            }
        }
    }

    @Test
    fun `removing an absent MCP reaction is idempotent`() {
        client("Alice").use { client ->
            val discussion = discussion(client)
            client.call("add_comment_reaction", discussion.arguments())
            client.call("remove_comment_reaction", discussion.arguments())

            val result = client.metadata(client.call("remove_comment_reaction", discussion.arguments()))

            assertTrue(result.path("reactions").isEmpty)
            assertTrue(comments(client, discussion)[0].path("reactions").isEmpty)
            assertEquals(0, count(discussion.root))
        }
    }

    @ParameterizedTest(name = "reject unsupported MCP emoji {0}")
    @ValueSource(strings = ["👍🏽", "not emoji", "", "👍👍"])
    fun `MCP rejects unsupported and skin-tone emojis`(emoji: String) {
        client("Alice").use { client ->
            val discussion = discussion(client)

            client.call("add_comment_reaction", discussion.arguments(emoji), expectError = true)

            assertTrue(comments(client, discussion)[0].path("reactions").isEmpty)
            assertEquals(0, count(discussion.root))
        }
    }

    @ParameterizedTest(name = "{0} rejects another artifact's comment")
    @ValueSource(strings = ["add_comment_reaction", "remove_comment_reaction"])
    fun `MCP reaction writes enforce artifact ownership`(tool: String) {
        client("Alice").use { client ->
            val discussion = discussion(client)
            client.call("add_comment_reaction", discussion.arguments())
            val other = publish(client)

            client.call(tool, discussion.arguments() + ("id" to other), expectError = true)

            assertEquals(json.readTree("""[{"emoji":"👍","author":"Alice"}]"""), comments(client, discussion)[0].path("reactions"))
            assertEquals(1, count(discussion.root))
        }
    }

    @ParameterizedTest(name = "{0} rejects deleted comments")
    @ValueSource(strings = ["add_comment_reaction", "remove_comment_reaction"])
    fun `MCP reaction writes reject deleted comments`(tool: String) {
        client("Alice").use { client ->
            val discussion = discussion(client)
            client.call("add_comment_reaction", discussion.arguments())
            mvc.perform(delete("/p/${discussion.id}/comments/${discussion.root}").header("Authorization", "Bearer ${token("Alice")}")
                .with(csrf())).andExpect(status().isNoContent)

            client.call(tool, discussion.arguments(), expectError = true)

            assertEquals(1, count(discussion.root))
            assertTrue(client.metadata(client.call("list_discussion_threads", mapOf("id" to discussion.id))).isEmpty)
        }
    }
}
