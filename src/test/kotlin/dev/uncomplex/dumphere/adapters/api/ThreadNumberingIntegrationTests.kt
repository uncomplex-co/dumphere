package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.ArtifactVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode

class ThreadNumberingIntegrationTests : CommentMutationTestSupport() {
    @Test
    fun `a reply reorders document-level and anchored discussions without changing thread numbers`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val older = root(id, "Older thread", emptyMap())
        val newer = root(id, "Newer thread", mapOf("xpath" to "/html/body/p", "relativeX" to 0.25, "relativeY" to 0.75))
        val olderId = older.path("threadId").asLong()
        val newerId = newer.path("threadId").asLong()
        val before = snapshot(id)
        assertEquals(listOf(1L, 2L), before.threads.map { it["number"] })
        assertNull(before.threads.first()["xpath"])
        assertEquals("/html/body/p", before.threads.last()["xpath"])
        assertEquals(0.25, before.threads.last()["relative_x"])
        assertEquals(0.75, before.threads.last()["relative_y"])
        assertEquals(
            listOf(newerId, olderId),
            listedComments(id).asArray().values().map { it.path("threadId").asLong() },
        )

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/threads/$olderId/comments")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Latest reply to older thread","version":1}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(201, response.status)
        val reply = json.readTree(response.contentAsString)
        assertEquals(olderId, reply.path("threadId").asLong())
        assertEquals(1, reply.path("threadNumber").asInt())
        val listed = listedComments(id).asArray().values().toList()
        assertEquals(listOf(olderId, olderId, newerId), listed.map { it.path("threadId").asLong() })
        assertEquals(listOf(1, 1, 2), listed.map { it.path("threadNumber").asInt() })
        assertEquals(
            listOf("Older thread", "Latest reply to older thread", "Newer thread"),
            listed.map { it.path("body").asString() },
        )
        val after = snapshot(id)
        assertEquals(before.threads, after.threads)
        assertEquals(before.comments, after.comments.take(2))
        assertEquals(3, after.comments.size)
        val storedReply = after.comments.last()
        assertEquals(reply.path("id").asLong(), storedReply["id"])
        assertEquals("Latest reply to older thread", storedReply["body"])
        assertEquals(olderId, storedReply["thread_id"])
        assertEquals(before.versions.single()["id"], storedReply["created_in_version_id"])
        assertEquals(before.page, after.page)
        assertEquals(before.versions, after.versions)
    }

    @Test
    fun `a new thread gets the next thread number independently of the comment count`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val older = root(id, "Older thread", emptyMap())
        root(id, "Newer thread", emptyMap())
        createReply(id, older, "Reply to older thread")
        val before = snapshot(id)
        assertEquals(listOf(1L, 2L), before.threads.map { it["number"] })
        assertEquals(3, before.comments.size)

        // Act
        val response = postRoot(id, "Third thread", emptyMap())

        // Assert
        assertEquals(201, response.status)
        val created = json.readTree(response.contentAsString)
        assertEquals(3, created.path("threadNumber").asInt())
        val after = snapshot(id)
        assertEquals(listOf(1L, 2L, 3L), after.threads.map { it["number"] })
        assertEquals(before.threads, after.threads.take(2))
        val storedThread = after.threads.last()
        assertEquals(created.path("threadId").asLong(), storedThread["id"])
        assertEquals(id, storedThread["artifact_id"])
        assertEquals(before.comments, after.comments.take(3))
        assertEquals(4, after.comments.size)
        val storedComment = after.comments.last()
        assertEquals(created.path("id").asLong(), storedComment["id"])
        assertEquals("Third thread", storedComment["body"])
        assertEquals(storedThread["id"], storedComment["thread_id"])
        assertEquals(before.versions.single()["id"], storedComment["created_in_version_id"])
        assertEquals(before.page + mapOf("last_discussion_thread_number" to 3L), after.page)
        assertEquals(before.versions, after.versions)
    }

    private fun root(id: String, body: String, anchor: Map<String, Any>): JsonNode {
        val response = postRoot(id, body, anchor)
        assertEquals(201, response.status)
        return json.readTree(response.contentAsString).also { assertEquals(body, commentRow(it)["body"]) }
    }

    private fun postRoot(id: String, body: String, anchor: Map<String, Any>): MockHttpServletResponse =
        mvc.perform(
                post("/p/$id/comments")
                    .with(csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(mapOf("body" to body, "version" to 1) + anchor))
            )
            .andReturn()
            .response
}
