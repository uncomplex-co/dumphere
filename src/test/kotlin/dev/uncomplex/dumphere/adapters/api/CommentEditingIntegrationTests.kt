package dev.uncomplex.dumphere.adapters.api

import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch

class CommentEditingIntegrationTests : CommentMutationTestSupport() {
    companion object {
        @JvmStatic
        fun editableComments() =
            listOf(
                Arguments.of("root", 0, listOf("Revised <b>plain text</b>", "Original reply")),
                Arguments.of("reply", 1, listOf("Original", "Revised <b>plain text</b>")),
            )
    }

    @ParameterizedTest(name = "editing the {0} preserves identity, thread placement, and other persisted state")
    @MethodSource("editableComments")
    fun `authors can edit their comments without changing the rest of the discussion`(
        scenario: String,
        targetIndex: Int,
        expectedBodies: List<String>,
    ) {
        // Arrange
        val id = publish()
        val root = createComment(id, "Original")
        val reply = createReply(id, root, "Original reply")
        val target = listOf(root, reply)[targetIndex]
        val identity = member()
        val before = snapshot(id)
        val original = before.comments[targetIndex]

        // Act
        val response =
            mvc.perform(
                    patch("/p/$id/comments/${target.path("id").asLong()}")
                        .with(identity)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"  Revised <b>plain text</b>  "}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(200, response.status, scenario)
        val edited = json.readTree(response.contentAsString)
        assertEquals("Revised <b>plain text</b>", edited.path("body").asString())
        assertTrue(edited.path("canEdit").asBoolean())
        assertEquals(target.path("id"), edited.path("id"))
        assertEquals(root.path("threadId"), edited.path("threadId"))
        assertEquals(1, edited.path("threadNumber").asInt())
        assertEquals("Alex Morgan", edited.path("author").asString())
        assertEquals("/html/body/p", edited.path("xpath").asString())
        assertEquals(0.5, edited.path("relativeX").asDouble())
        assertEquals(0.5, edited.path("relativeY").asDouble())
        val createdAt = (original["created_at"] as Timestamp).toInstant()
        assertEquals(createdAt, Instant.parse(edited.path("createdAt").asString()))
        val editedAt = Instant.parse(edited.path("editedAt").asString())
        assertTrue(editedAt.isAfter(createdAt))
        val expectedComments = before.comments.toMutableList()
        expectedComments[targetIndex] =
            original +
                mapOf(
                    "body" to "Revised <b>plain text</b>",
                    "edited_at" to Timestamp.from(editedAt),
                )
        assertEquals(before.copy(comments = expectedComments), snapshot(id))
        val listed = listedComments(id).asArray().values().toList()
        assertEquals(expectedBodies, listed.map { it.path("body").asString() })
        assertEquals(edited, listed[targetIndex])
    }
}
