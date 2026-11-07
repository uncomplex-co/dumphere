package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.ArtifactVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class CommentDeletionIntegrationTests : CommentMutationTestSupport() {
    @Test
    fun `reply deletion hides only that reply and preserves its data`() {
        // Arrange
        val id = publish()
        val root = createComment(id)
        val ownReply = createReply(id, root, "My reply")
        val otherReply = createReply(id, root, "Another reply", member(subject = "another-author"))
        val identity = member()
        val before = snapshot(id)
        val replyBefore = commentRow(ownReply)
        val rootBefore = commentRow(root)
        val otherBefore = commentRow(otherReply)

        // Act
        val response =
            mvc.perform(delete("/p/$id/comments/${ownReply.path("id").asLong()}").with(identity).with(csrf()))
                .andReturn()
                .response

        // Assert
        assertEquals(204, response.status)
        assertEquals(
            listOf("Root", "Another reply"),
            listedComments(id).asArray().values().map { it.path("body").asString() },
        )
        val replyAfter = commentRow(ownReply)
        assertNotNull(replyAfter["deleted_at"])
        assertEquals(replyBefore.filterKeys { it != "deleted_at" }, replyAfter.filterKeys { it != "deleted_at" })
        assertEquals(rootBefore, commentRow(root))
        assertEquals(otherBefore, commentRow(otherReply))
        val after = snapshot(id)
        assertEquals(before.page, after.page)
        assertEquals(before.versions, after.versions)
        assertEquals(before.threads, after.threads)
    }

    @Test
    fun `root deletion hides the thread and soft deletes its remaining comments`() {
        // Arrange
        val id = publish()
        val root = createComment(id)
        val ownReply = createReply(id, root, "My reply")
        val otherReply = createReply(id, root, "Another reply", member(subject = "another-author"))
        val identity = member()
        mvc.perform(delete("/p/$id/comments/${ownReply.path("id").asLong()}").with(identity).with(csrf()))
            .andExpect(status().isNoContent)
        val before = snapshot(id)
        val deletedReply = commentRow(ownReply)

        // Act
        val response =
            mvc.perform(delete("/p/$id/comments/${root.path("id").asLong()}").with(identity).with(csrf()))
                .andReturn()
                .response

        // Assert
        assertEquals(204, response.status)
        assertEquals(0, listedComments(id).size())
        val after = snapshot(id)
        assertNotNull(after.threads.single()["deleted_at"])
        assertNotNull(commentRow(root)["deleted_at"])
        assertNotNull(commentRow(otherReply)["deleted_at"])
        assertEquals(3, after.comments.size)
        assertEquals(
            before.comments.map { row -> row.filterKeys { it != "deleted_at" } },
            after.comments.map { row -> row.filterKeys { it != "deleted_at" } },
        )
        assertEquals(
            before.threads.single().filterKeys { it != "deleted_at" },
            after.threads.single().filterKeys { it != "deleted_at" },
        )
        assertEquals(deletedReply, commentRow(ownReply))
        assertEquals(before.page, after.page)
        assertEquals(before.versions, after.versions)
    }

    @Test
    fun `editing a deleted reply is not found and cannot resurrect it`() {
        // Arrange
        val id = publish()
        val root = createComment(id)
        val reply = createReply(id, root)
        val identity = member()
        mvc.perform(delete("/p/$id/comments/${reply.path("id").asLong()}").with(identity).with(csrf()))
            .andExpect(status().isNoContent)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    patch("/p/$id/comments/${reply.path("id").asLong()}")
                        .with(identity)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Do not resurrect"}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(404, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `replies to a deleted thread are not found and cannot resurrect it`() {
        // Arrange
        val id = publish()
        val root = createComment(id)
        val identity = member()
        mvc.perform(delete("/p/$id/comments/${root.path("id").asLong()}").with(identity).with(csrf()))
            .andExpect(status().isNoContent)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/threads/${root.path("threadId").asLong()}/comments")
                        .with(identity)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"No replies to deleted threads","version":1}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(404, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `repeated deletion of a root comment is not found and leaves rows unchanged`() {
        // Arrange
        val id = publish()
        val root = createComment(id)
        val identity = member()
        mvc.perform(delete("/p/$id/comments/${root.path("id").asLong()}").with(identity).with(csrf()))
            .andExpect(status().isNoContent)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(delete("/p/$id/comments/${root.path("id").asLong()}").with(identity).with(csrf()))
                .andReturn()
                .response

        // Assert
        assertEquals(404, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `new threads do not reuse a deleted thread's number`() {
        // Arrange
        val id = publish()
        val root = createComment(id)
        val identity = member()
        assertEquals(1, root.path("threadNumber").asInt())
        mvc.perform(delete("/p/$id/comments/${root.path("id").asLong()}").with(identity).with(csrf()))
            .andExpect(status().isNoContent)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/comments")
                        .with(identity)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Next thread","version":1}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(201, response.status)
        val created = json.readTree(response.contentAsString)
        assertEquals(2, created.path("threadNumber").asInt())
        assertEquals(
            2L,
            jdbc.queryForObject(
                "select number from comment_threads where id = ?",
                Long::class.java,
                created.path("threadId").asLong(),
            ),
        )
        assertEquals("Next thread", commentRow(created)["body"])
        assertEquals(before.versions, snapshot(id).versions)
        assertEquals(1, snapshot(id).page["current_version"])
    }

    @Test
    fun `deleting another author's comment is forbidden`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val identity = member(subject = "another-author")
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(delete("/p/$id/comments/${root.path("id").asLong()}").with(identity).with(csrf()))
                .andReturn()
                .response

        // Assert
        assertEquals(403, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `anonymous deletion is forbidden`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(delete("/p/$id/comments/${root.path("id").asLong()}").with(csrf())).andReturn().response

        // Assert
        assertEquals(403, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `deletion without CSRF is forbidden`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val identity = member()
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(delete("/p/$id/comments/${root.path("id").asLong()}").with(identity)).andReturn().response

        // Assert
        assertEquals(403, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `team members cannot delete an anonymously authored comment`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id, identity = anonymous())
        val identity = member()
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(delete("/p/$id/comments/${root.path("id").asLong()}").with(identity).with(csrf()))
                .andReturn()
                .response

        // Assert
        assertEquals(403, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `deleting a comment through another artifact is not found`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val otherId = publish(ArtifactVisibility.PUBLIC)
        val identity = member()
        val before = snapshot(id)
        val otherBefore = snapshot(otherId)

        // Act
        val response =
            mvc.perform(delete("/p/$otherId/comments/${root.path("id").asLong()}").with(identity).with(csrf()))
                .andReturn()
                .response

        // Assert
        assertEquals(404, response.status)
        assertEquals(before, snapshot(id))
        assertEquals(otherBefore, snapshot(otherId))
    }

    @Test
    fun `deleting a missing comment is not found`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        createComment(id)
        val identity = member()
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(delete("/p/$id/comments/${Long.MAX_VALUE}").with(identity).with(csrf())).andReturn().response

        // Assert
        assertEquals(404, response.status)
        assertEquals(before, snapshot(id))
    }
}
