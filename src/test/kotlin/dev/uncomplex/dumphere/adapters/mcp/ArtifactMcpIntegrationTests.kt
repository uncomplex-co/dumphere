package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.testing.McpTestClient
import dev.uncomplex.dumphere.testing.PostgresTestDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
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
@AutoConfigureMockMvc
class ArtifactMcpIntegrationTests {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jwtEncoder: JwtEncoder

    @Autowired private lateinit var json: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mvc: MockMvc

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

    @Test
    fun agentsDiscussArtifactsAcrossVersionsWithoutMixingThreadsOrChangingContent() {
        val token = jwtEncoder.encode(
            JwtEncoderParameters.from(
                JwtClaimsSet.builder().subject("comment-agent").claim("email", "comments@example.com")
                    .claim("name", "Comment agent").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build(),
            ),
        ).tokenValue
        McpTestClient(port, token, json).use { client ->
            client.initialize()
            fun publish() = client.metadata(client.call("publish_html", mapOf("contents" to "<h1>Discuss</h1>", "format" to "html")))
                .path("id").asString()
            val id = publish()
            val otherId = publish()
            val empty = client.metadata(client.call("list_discussion_threads", mapOf("id" to id)))
            assertTrue(empty.isArray)
            assertEquals(0, empty.size())

            val first = client.metadata(client.call("start_discussion_thread", mapOf("id" to id, "body" to "  First topic  ")))
            val thread = first.path("threadId").asLong()
            assertTrue(thread > 0)
            assertEquals("First topic", first.path("body").asString())
            assertEquals(1, first.path("threadNumber").asInt())
            assertEquals("First topic", jdbc.queryForObject("select body from comments where id = ?", String::class.java, first.path("id").asLong()))
            assertEquals("comment-agent", jdbc.queryForObject(
                "select u.subject from comments c join users u on u.id = c.author_user_id where c.id = ?",
                String::class.java, first.path("id").asLong(),
            ))
            val second = client.metadata(client.call("start_discussion_thread", mapOf("id" to id, "body" to "Other topic")))
            assertEquals(2, second.path("threadNumber").asInt())
            client.call("update_html", mapOf("id" to id, "contents" to "<h1>Revised</h1>"))
            val reply = client.metadata(client.call("reply_to_thread", mapOf("id" to id, "thread_id" to thread, "body" to "Reply on v2")))
            assertEquals(thread, reply.path("threadId").asLong())
            assertEquals(1, reply.path("threadNumber").asInt())
            assertEquals(2, jdbc.queryForObject(
                "select v.version from comments c join html_page_versions v on v.id = c.created_in_version_id where c.id = ?",
                Int::class.java, reply.path("id").asLong(),
            ))
            val followUp = client.metadata(client.call("reply_to_thread", mapOf("id" to id, "thread_id" to thread, "body" to "Follow-up")))
            assertEquals(2, jdbc.queryForObject(
                "select v.version from comments c join html_page_versions v on v.id = c.created_in_version_id where c.id = ?",
                Int::class.java, followUp.path("id").asLong(),
            ))
            val summaries = client.metadata(client.call("list_discussion_threads", mapOf("id" to id)))
            assertTrue(summaries.isArray)
            assertEquals(2, summaries.size())
            assertEquals(thread, summaries[0].path("threadId").asLong())
            assertEquals(1, summaries[0].path("threadNumber").asInt())
            assertEquals(2, summaries[1].path("threadNumber").asInt())
            assertEquals("First topic", summaries[0].path("firstComment").asString())
            assertEquals(2, summaries[0].path("replyCount").asInt())
            assertEquals(second.path("threadId").asLong(), summaries[1].path("threadId").asLong())
            assertEquals(0, summaries[1].path("replyCount").asInt())
            val discussion = client.metadata(client.call("list_thread_comments", mapOf("id" to id, "thread_id" to thread)))
            assertTrue(discussion.isArray)
            assertEquals(listOf(1, 1, 1), discussion.asArray().values().map { it.path("threadNumber").asInt() })
            assertEquals(listOf("First topic", "Reply on v2", "Follow-up"), discussion.asArray().values().map { it.path("body").asString() })

            for (tool in listOf("list_thread_comments", "reply_to_thread")) {
                client.call(tool, mapOf("id" to otherId, "thread_id" to thread, "body" to "Wrong artifact"), expectError = true)
                client.call(tool, mapOf("id" to id, "thread_id" to Long.MAX_VALUE, "body" to "Missing thread"), expectError = true)
            }
            for (body in listOf("  ", "x".repeat(10001))) {
                client.call("start_discussion_thread", mapOf("id" to id, "body" to body), expectError = true)
                client.call("reply_to_thread", mapOf("id" to id, "thread_id" to thread, "body" to body), expectError = true)
            }
            for (tool in listOf("list_discussion_threads", "list_thread_comments", "start_discussion_thread", "reply_to_thread")) {
                client.call(tool, mapOf("id" to "nonexistent_artifact_id", "thread_id" to thread, "body" to "Missing artifact"), expectError = true)
            }
            assertEquals(4, jdbc.queryForObject(
                "select count(*) from comments c join comment_threads t on t.id = c.thread_id where t.artifact_id = ?", Int::class.java, id,
            ))
            assertEquals(2, jdbc.queryForObject("select count(*) from comment_threads where artifact_id = ?", Int::class.java, id))
            assertEquals(0, jdbc.queryForObject("select count(*) from comment_threads where artifact_id = ?", Int::class.java, otherId))
            assertEquals("<h1>Revised</h1>", contents(id))
            assertEquals(2, jdbc.queryForObject("select current_version from html_pages where id = ?", Int::class.java, id))

            val resolved = client.metadata(client.call("resolve_discussion_thread", mapOf("id" to id, "thread_id" to thread)))
            assertEquals(thread, resolved.path("threadId").asLong())
            assertEquals(1, resolved.path("threadNumber").asInt())
            val resolvedAt = Instant.parse(resolved.path("resolvedAt").asString())
            assertEquals(resolvedAt, jdbc.queryForObject("select resolved_at from comment_threads where id = ?", Timestamp::class.java, thread)?.toInstant())
            val repeatedResolve = client.metadata(client.call("resolve_discussion_thread", mapOf("id" to id, "thread_id" to thread)))
            assertEquals(resolved.path("resolvedAt"), repeatedResolve.path("resolvedAt"))
            val resolvedThreads = client.metadata(client.call("list_discussion_threads", mapOf("id" to id)))
            assertTrue(resolvedThreads.isArray)
            assertEquals(2, resolvedThreads.size())
            assertEquals(resolved.path("resolvedAt"), resolvedThreads[0].path("resolvedAt"))
            val resolvedComments = client.metadata(client.call("list_thread_comments", mapOf("id" to id, "thread_id" to thread)))
            assertTrue(resolvedComments.isArray)
            assertEquals(3, resolvedComments.size())
            assertTrue(resolvedComments.asArray().values().all { it.path("resolvedAt") == resolved.path("resolvedAt") })
            val reopened = client.metadata(client.call("reopen_discussion_thread", mapOf("id" to id, "thread_id" to thread)))
            assertTrue(reopened.path("resolvedAt").isNull)
            assertNull(jdbc.queryForObject("select resolved_at from comment_threads where id = ?", Timestamp::class.java, thread))
            assertTrue(client.metadata(client.call("reopen_discussion_thread", mapOf("id" to id, "thread_id" to thread))).path("resolvedAt").isNull)
            val reopenedThreads = client.metadata(client.call("list_discussion_threads", mapOf("id" to id)))
            assertTrue(reopenedThreads.isArray)
            assertTrue(reopenedThreads[0].path("resolvedAt").isNull)
            for (tool in listOf("resolve_discussion_thread", "reopen_discussion_thread")) {
                client.call(tool, mapOf("id" to otherId, "thread_id" to thread), expectError = true)
                client.call(tool, mapOf("id" to id, "thread_id" to Long.MAX_VALUE), expectError = true)
                client.call(tool, mapOf("id" to "nonexistent_artifact_id", "thread_id" to thread), expectError = true)
            }

            fun editComment(commentId: Long, body: String) = json.readTree(mvc.perform(
                patch("/p/$id/comments/$commentId").header("Authorization", "Bearer $token").with(csrf())
                    .contentType("application/json").content(json.writeValueAsString(mapOf("body" to body))),
            ).andExpect(status().isOk).andReturn().response.contentAsString)
            val firstEdit = editComment(first.path("id").asLong(), "Updated first topic")
            val lastEdit = editComment(first.path("id").asLong(), "Final first topic")
            val editedReply = editComment(reply.path("id").asLong(), "Revised reply")
            assertTrue(!Instant.parse(lastEdit.path("editedAt").asString()).isBefore(Instant.parse(firstEdit.path("editedAt").asString())))
            val editedDiscussion = client.metadata(client.call("list_thread_comments", mapOf("id" to id, "thread_id" to thread)))
            assertTrue(editedDiscussion.isArray)
            assertEquals(3, editedDiscussion.size())
            assertEquals("Final first topic", editedDiscussion[0].path("body").asString())
            assertEquals(lastEdit.path("editedAt"), editedDiscussion[0].path("editedAt"))
            assertEquals("Revised reply", editedDiscussion[1].path("body").asString())
            assertEquals(editedReply.path("editedAt"), editedDiscussion[1].path("editedAt"))
            assertTrue(editedDiscussion[2].path("editedAt").isNull)
            assertEquals(
                jdbc.queryForObject("select edited_at from comments where id = ?", Timestamp::class.java, first.path("id").asLong())?.toInstant(),
                Instant.parse(editedDiscussion[0].path("editedAt").asString()),
            )
            val editedSummaries = client.metadata(client.call("list_discussion_threads", mapOf("id" to id)))
            assertTrue(editedSummaries.isArray)
            assertEquals("Final first topic", editedSummaries[0].path("firstComment").asString())

            mvc.perform(delete("/p/$id/comments/${followUp.path("id").asLong()}").header("Authorization", "Bearer $token").with(csrf()))
                .andExpect(status().isNoContent)
            val afterReplyDeletion = client.metadata(client.call("list_thread_comments", mapOf("id" to id, "thread_id" to thread)))
            assertTrue(afterReplyDeletion.isArray)
            assertEquals(listOf("Final first topic", "Revised reply"), afterReplyDeletion.asArray().values().map { it.path("body").asString() })
            val updatedSummaries = client.metadata(client.call("list_discussion_threads", mapOf("id" to id)))
            assertTrue(updatedSummaries.isArray)
            assertEquals(2, updatedSummaries.size())
            assertEquals(1, updatedSummaries[0].path("replyCount").asInt())

            mvc.perform(delete("/p/$id/comments/${first.path("id").asLong()}").header("Authorization", "Bearer $token").with(csrf()))
                .andExpect(status().isNoContent)
            val surviving = client.metadata(client.call("list_discussion_threads", mapOf("id" to id)))
            assertTrue(surviving.isArray)
            assertEquals(1, surviving.size())
            assertEquals(second.path("threadId"), surviving[0].path("threadId"))
            client.call("list_thread_comments", mapOf("id" to id, "thread_id" to thread), expectError = true)
            client.call("reply_to_thread", mapOf("id" to id, "thread_id" to thread, "body" to "Cannot resurrect"), expectError = true)
            client.call("resolve_discussion_thread", mapOf("id" to id, "thread_id" to thread), expectError = true)
            client.call("reopen_discussion_thread", mapOf("id" to id, "thread_id" to thread), expectError = true)
            assertEquals(3, jdbc.queryForObject("select count(*) from comments where thread_id = ? and deleted_at is not null", Int::class.java, thread))
            assertNotNull(jdbc.queryForObject("select deleted_at from comment_threads where id = ?", Timestamp::class.java, thread))
            assertEquals("Final first topic", jdbc.queryForObject("select body from comments where id = ?", String::class.java, first.path("id").asLong()))
        }
    }

    private fun contents(id: String) =
        jdbc.queryForObject(
            "select v.html from html_page_versions v join html_pages p on p.id = v.page_id and p.current_version = v.version where p.id = ?",
            String::class.java,
            id,
        )
}
