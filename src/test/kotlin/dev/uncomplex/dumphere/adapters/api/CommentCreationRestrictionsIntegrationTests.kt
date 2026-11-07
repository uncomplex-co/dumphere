package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.ArtifactVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

class CommentCreationRestrictionsIntegrationTests : CommentMutationTestSupport() {
    @Test
    fun `anonymous comment reads on an internal artifact are unauthorized`() {
        // Arrange
        val id = publish()
        val before = snapshot(id)

        // Act
        val response = mvc.perform(get("/p/$id/comments")).andReturn().response

        // Assert
        assertEquals(401, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `outside-domain comment reads on an internal artifact are forbidden`() {
        // Arrange
        val id = publish()
        val identity = member(email = "outsider@outside.test")
        val before = snapshot(id)

        // Act
        val response = mvc.perform(get("/p/$id/comments").with(identity)).andReturn().response

        // Assert
        assertEquals(403, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `team members can read comments on an internal artifact`() {
        // Arrange
        val id = publish()
        val root = createComment(id)
        val identity = member()
        val before = snapshot(id)

        // Act
        val response = mvc.perform(get("/p/$id/comments").with(identity)).andReturn().response

        // Assert
        assertEquals(200, response.status)
        assertEquals(
            listOf(root.path("id").asLong()),
            json.readTree(response.contentAsString).asArray().values().map { it.path("id").asLong() },
        )
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `anonymous comment creation on an internal artifact is unauthorized`() {
        // Arrange
        val id = publish()
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/comments")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Forbidden","version":1}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(401, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `public comment creation without CSRF is forbidden`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/comments")
                        .contentType("application/json")
                        .content("""{"body":"No CSRF","version":1}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(403, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `team members can create a persisted comment on a public artifact`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val identity = member()
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/comments")
                        .with(identity)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Member comment","version":1}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(201, response.status)
        val created = json.readTree(response.contentAsString)
        assertEquals("Member comment", created.path("body").asString())
        val after = snapshot(id)
        assertEquals(created.path("id").asLong(), after.comments.single()["id"])
        assertEquals("Member comment", after.comments.single()["body"])
        assertEquals(created.path("threadId").asLong(), after.threads.single()["id"])
        assertEquals(before.versions.single()["id"], after.comments.single()["created_in_version_id"])
        assertEquals(
            "different-team-member",
            jdbc.queryForObject(
                "select subject from users where id = ?",
                String::class.java,
                after.comments.single()["author_user_id"],
            ),
        )
        assertEquals(before.versions, after.versions)
    }

    @ParameterizedTest(name = "comment creation rejects {0} without changing persisted state")
    @MethodSource("invalidComments")
    fun `invalid comments leave no threads or other changes`(scenario: String, body: String, version: Int) {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/comments")
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
    fun `revoking public access makes anonymous comment reads unauthorized`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        createComment(id)
        revokePublicAccess(id)
        val before = snapshot(id)

        // Act
        val response = mvc.perform(get("/p/$id/comments")).andReturn().response

        // Assert
        assertEquals(401, response.status)
        assertEquals(before, snapshot(id))
    }

    @Test
    fun `revoking public access prevents anonymous comment creation`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        createComment(id)
        revokePublicAccess(id)
        val before = snapshot(id)

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/comments")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Revoked","version":1}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(401, response.status)
        assertEquals(before, snapshot(id))
    }
}
