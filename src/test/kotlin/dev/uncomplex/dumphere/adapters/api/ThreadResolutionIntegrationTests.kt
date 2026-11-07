package dev.uncomplex.dumphere.adapters.api

import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode

class ThreadResolutionIntegrationTests : CommentMutationTestSupport() {
    @Test
    fun `resolving an open thread persists the reviewer and timestamp without changing its discussion`() {
        // Arrange
        val (id, root) = discussion()
        val identity = member(subject = "reviewer")
        val reviewerId = jdbc.queryForObject("select id from users where subject = ?", Long::class.java, "reviewer")
        val before = snapshot(id)
        assertNull(before.threads.single()["resolved_at"])
        assertNull(before.threads.single()["resolved_by_user_id"])
        val startedAt = Instant.now()

        // Act
        val response = resolution(id, root, true, identity)

        // Assert
        assertEquals(200, response.status)
        val result = json.readTree(response.contentAsString)
        assertEquals(root.path("threadId"), result.path("threadId"))
        assertEquals(1, result.path("threadNumber").asInt())
        val resolvedAt = Instant.parse(result.path("resolvedAt").asString())
        assertTrue(!resolvedAt.isBefore(startedAt.truncatedTo(ChronoUnit.MICROS)))
        assertTrue(!resolvedAt.isAfter(Instant.now()))
        val after = snapshot(id)
        assertEquals(resolvedAt, (after.threads.single()["resolved_at"] as Timestamp).toInstant())
        assertEquals(assertNotNull(reviewerId), after.threads.single()["resolved_by_user_id"])
        assertOnlyResolutionChanged(before, after)
        assertEquals(
            listOf(resolvedAt, resolvedAt),
            listedComments(id).asArray().values().map { Instant.parse(it.path("resolvedAt").asString()) },
        )
    }

    @Test
    fun `resolving an already resolved thread preserves the original resolver and timestamp`() {
        // Arrange
        val (id, root) = resolvedDiscussion()
        val identity = member(subject = "different-reviewer")
        val before = snapshot(id)
        val resolvedAt = (before.threads.single()["resolved_at"] as Timestamp).toInstant()

        // Act
        val response = resolution(id, root, true, identity)

        // Assert
        assertEquals(200, response.status)
        val result = json.readTree(response.contentAsString)
        assertEquals(root.path("threadId"), result.path("threadId"))
        assertEquals(1, result.path("threadNumber").asInt())
        assertEquals(resolvedAt, Instant.parse(result.path("resolvedAt").asString()))
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `reopening a resolved thread clears its resolution without changing its discussion`() {
        // Arrange
        val (id, root) = resolvedDiscussion()
        val identity = member(subject = "reopening-reviewer")
        val before = snapshot(id)

        // Act
        val response = resolution(id, root, false, identity)

        // Assert
        assertEquals(200, response.status)
        val result = json.readTree(response.contentAsString)
        assertEquals(root.path("threadId"), result.path("threadId"))
        assertEquals(1, result.path("threadNumber").asInt())
        assertTrue(result.path("resolvedAt").isNull)
        val after = snapshot(id)
        assertNull(after.threads.single()["resolved_at"])
        assertNull(after.threads.single()["resolved_by_user_id"])
        assertOnlyResolutionChanged(before, after)
        assertEquals(listOf(true, true), listedComments(id).asArray().values().map { it.path("resolvedAt").isNull })
    }

    @Test
    fun `reopening an already reopened thread leaves its discussion unchanged`() {
        // Arrange
        val (id, root) = resolvedDiscussion()
        val reopening = resolution(id, root, false, member(subject = "reopening-reviewer"))
        assertEquals(200, reopening.status)
        assertTrue(json.readTree(reopening.contentAsString).path("resolvedAt").isNull)
        val identity = member(subject = "different-reviewer")
        val before = snapshot(id)
        assertNull(before.threads.single()["resolved_at"])
        assertNull(before.threads.single()["resolved_by_user_id"])

        // Act
        val response = resolution(id, root, false, identity)

        // Assert
        assertEquals(200, response.status)
        val result = json.readTree(response.contentAsString)
        assertEquals(root.path("threadId"), result.path("threadId"))
        assertEquals(1, result.path("threadNumber").asInt())
        assertTrue(result.path("resolvedAt").isNull)
        assertEquals(before, snapshot(id))
    }

    private fun discussion(): Pair<String, JsonNode> {
        val id = publish()
        val root = createComment(id, "Please review")
        createReply(id, root, "Reviewed")
        return id to root
    }

    private fun resolvedDiscussion(): Pair<String, JsonNode> {
        val (id, root) = discussion()
        val response = resolution(id, root, true, member(subject = "original-reviewer"))
        assertEquals(200, response.status)
        val result = json.readTree(response.contentAsString)
        val stored = snapshot(id).threads.single()
        assertEquals(
            Instant.parse(result.path("resolvedAt").asString()),
            (stored["resolved_at"] as Timestamp).toInstant(),
        )
        assertEquals(
            "original-reviewer",
            jdbc.queryForObject(
                "select subject from users where id = ?",
                String::class.java,
                stored["resolved_by_user_id"],
            ),
        )
        return id to root
    }

    private fun resolution(
        id: String,
        root: JsonNode,
        resolved: Boolean,
        identity: RequestPostProcessor,
    ): MockHttpServletResponse =
        mvc.perform(
                patch("/p/$id/threads/${root.path("threadId").asLong()}/resolution")
                    .with(identity)
                    .with(csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(mapOf("resolved" to resolved)))
            )
            .andReturn()
            .response

    private fun assertOnlyResolutionChanged(before: DiscussionSnapshot, after: DiscussionSnapshot) {
        val resolutionFields = setOf("resolved_at", "resolved_by_user_id")
        assertEquals(
            before.threads.single().filterKeys { it !in resolutionFields },
            after.threads.single().filterKeys { it !in resolutionFields },
        )
        assertEquals(before.copy(threads = after.threads), after)
    }
}
