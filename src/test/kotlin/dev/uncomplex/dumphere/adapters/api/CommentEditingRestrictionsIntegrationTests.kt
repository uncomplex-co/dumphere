package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.ArtifactVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch

class CommentEditingRestrictionsIntegrationTests : CommentMutationTestSupport() {
    @Test
    fun `editing another author's comment is forbidden`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val identity = member(subject = "not-the-author")
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    patch("/p/$id/comments/${root.path("id").asLong()}")
                        .with(identity)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Changed"}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(403, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `anonymous edits are forbidden`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    patch("/p/$id/comments/${root.path("id").asLong()}")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Changed"}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(403, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `edits without CSRF are forbidden`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val identity = member()
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    patch("/p/$id/comments/${root.path("id").asLong()}")
                        .with(identity)
                        .contentType("application/json")
                        .content("""{"body":"Changed"}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(403, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `team members cannot edit an anonymously authored comment`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id, identity = anonymous())
        val identity = member()
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    patch("/p/$id/comments/${root.path("id").asLong()}")
                        .with(identity)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Changed"}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(403, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `editing a comment through another artifact is not found`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val otherId = publish(ArtifactVisibility.PUBLIC)
        val identity = member()
        val before = snapshot(id)
        val otherBefore = snapshot(otherId)

        // Act
        val response =
            mvc.perform(
                    patch("/p/$otherId/comments/${root.path("id").asLong()}")
                        .with(identity)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Changed"}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(404, response.status)
        assertEquals(before, snapshot(id))
        assertEquals(otherBefore, snapshot(otherId))
    }

    @Test
    fun `editing a missing comment is not found`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        createComment(id)
        val identity = member()
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    patch("/p/$id/comments/${Long.MAX_VALUE}")
                        .with(identity)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Changed"}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(404, response.status)
        assertEquals(before, snapshot(id))
    }

    @ParameterizedTest(name = "comment editing rejects {0} without changing persisted state")
    @MethodSource("invalidBodies")
    fun `invalid edits leave the discussion unchanged`(scenario: String, body: String) {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val identity = member()
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    patch("/p/$id/comments/${root.path("id").asLong()}")
                        .with(identity)
                        .with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(mapOf("body" to body)))
                )
                .andReturn()
                .response

        // Assert
        assertEquals(400, response.status, scenario)
        assertEquals(before, snapshot(id))
    }

    @ParameterizedTest(name = "comment listing marks anonymous comments uneditable for viewer {0}")
    @ValueSource(strings = ["different-team-member", "another-member"])
    fun `anonymous comments are uneditable regardless of team viewer`(subject: String) {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id, identity = anonymous())
        val identity = member(subject = subject)
        val before = snapshot(id)

        // Act
        val response = mvc.perform(get("/p/$id/comments").with(identity)).andReturn().response

        // Assert
        assertEquals(200, response.status)
        val listed = json.readTree(response.contentAsString).single()
        assertEquals(root.path("id"), listed.path("id"))
        assertFalse(listed.path("canEdit").asBoolean())
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `comment listing marks another author's comment uneditable`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val root = createComment(id)
        val identity = member(subject = "not-the-author")
        val before = snapshot(id)

        // Act
        val response = mvc.perform(get("/p/$id/comments").with(identity)).andReturn().response

        // Assert
        assertEquals(200, response.status)
        val listed = json.readTree(response.contentAsString).single()
        assertEquals(root.path("id"), listed.path("id"))
        assertFalse(listed.path("canEdit").asBoolean())
        assertEquals(before, snapshot(id))
    }
}
