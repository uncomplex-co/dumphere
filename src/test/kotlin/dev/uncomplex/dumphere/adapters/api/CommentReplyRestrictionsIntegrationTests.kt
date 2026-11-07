package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.ArtifactVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

class CommentReplyRestrictionsIntegrationTests : CommentMutationTestSupport() {
    @Test
    fun `anonymous replies on an internal artifact are unauthorized`() {
        // Arrange
        val id = publish()
        val root = createComment(id)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/threads/${root.path("threadId").asLong()}/comments")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Reply","version":1}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(401, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `outside-domain replies on an internal artifact are forbidden`() {
        // Arrange
        val id = publish()
        val root = createComment(id)
        val identity = member(email = "outsider@outside.test")
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/threads/${root.path("threadId").asLong()}/comments")
                        .with(identity)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Reply","version":1}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(403, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `public replies without CSRF are forbidden`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/threads/${root.path("threadId").asLong()}/comments")
                        .contentType("application/json")
                        .content("""{"body":"Reply","version":1}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(403, response.status)
        assertEquals(before, snapshot(id))
    }

    @ParameterizedTest(name = "reply creation rejects {0} without changing persisted state")
    @MethodSource("invalidComments")
    fun `invalid replies leave the discussion unchanged`(scenario: String, body: String, version: Int) {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/threads/${root.path("threadId").asLong()}/comments")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(mapOf("body" to body, "version" to version)))
                )
                .andReturn()
                .response

        // Assert
        assertEquals(400, response.status, scenario)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `replies to a thread belonging to another artifact are not found`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val otherId = publish(ArtifactVisibility.PUBLIC)
        val before = snapshot(id)
        val otherBefore = snapshot(otherId)

        // Act
        val response =
            mvc.perform(
                    post("/p/$otherId/threads/${root.path("threadId").asLong()}/comments")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Reply","version":1}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(404, response.status)
        assertEquals(before, snapshot(id))
        assertEquals(otherBefore, snapshot(otherId))
    }

    @Test
    fun `replies to a missing thread are not found`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        createComment(id)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/threads/${Long.MAX_VALUE}/comments")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Reply","version":1}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(404, response.status)
        assertEquals(before, snapshot(id))
    }
}
