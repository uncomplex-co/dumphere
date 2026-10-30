package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.UserProvisioningService
import dev.uncomplex.dumphere.application.ArtifactVisibility
import dev.uncomplex.dumphere.application.HtmlPageStore
import dev.uncomplex.dumphere.application.PageContentFormat
import dev.uncomplex.dumphere.testing.PostgresTestDatabase
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = [
    "spring.security.oauth2.client.registration.google.client-id=test-client",
    "spring.security.oauth2.client.registration.google.client-secret=test-secret",
    "htmlshare.allowed-email-domain=example.com",
])
@AutoConfigureMockMvc
class CommentReactionsIntegrationTests {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var pages: HtmlPageStore
    @Autowired private lateinit var users: UserProvisioningService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var json: ObjectMapper

    companion object {
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = PostgresTestDatabase.configure(registry)
    }

    private fun member(subject: String = "reaction-alice") = oidcLogin().idToken {
        it.subject(subject).claim("email", "$subject@example.com").claim("name", subject)
    }

    private fun artifact(): String = pages.publish("Reaction test", "# Review", PageContentFormat.MARKDOWN,
        users.provision(AuthenticatedUser("reaction-owner", "owner@example.com", "Owner"))).id

    private fun comment(id: String): Long = json.readTree(mvc.perform(post("/p/$id/comments")
        .with(member()).with(csrf()).contentType("application/json")
        .content("""{"body":"Review this","version":1}"""))
        .andExpect(status().isCreated).andReturn().response.contentAsString).path("id").asLong()

    private fun react(id: String, comment: Long, emoji: String = "👍", reacted: Boolean = true, subject: String = "reaction-alice"): JsonNode =
        json.readTree(mvc.perform(put("/p/$id/comments/$comment/reaction").with(member(subject)).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(mapOf("emoji" to emoji, "reacted" to reacted))))
            .andExpect(status().isOk).andReturn().response.contentAsString)

    private fun listed(id: String, subject: String = "reaction-alice"): JsonNode = json.readTree(mvc.perform(get("/p/$id/comments").with(member(subject)))
        .andExpect(status().isOk).andReturn().response.contentAsString)

    @Test
    fun `different members share one counted emoji and see their own participation`() {
        val id = artifact()
        val comment = comment(id)
        react(id, comment)

        val result = react(id, comment, subject = "reaction-bob")

        assertEquals(2, result.path("reactions")[0].path("count").asInt())
        val reaction = listed(id)[0].path("reactions")[0]
        assertEquals("👍", reaction.path("emoji").asString())
        assertEquals(2, reaction.path("count").asInt())
        assertTrue(reaction.path("reacted").asBoolean())
        assertEquals(listOf("reaction-alice", "reaction-bob"), reaction.path("users").toList().map { it.asString() })
        assertFalse(listed(id, "reaction-charlie")[0].path("reactions")[0].path("reacted").asBoolean())
        assertEquals(2, jdbc.queryForObject("select count(*) from comment_reactions where comment_id = ?", Int::class.java, comment))
    }

    @Test
    fun `repeated add is idempotent and a person may use multiple emojis`() {
        val id = artifact()
        val comment = comment(id)
        react(id, comment)
        react(id, comment, "🎉")

        val result = react(id, comment)

        assertEquals(listOf("👍", "🎉"), result.path("reactions").toList().map { it.path("emoji").asString() })
        assertEquals(listOf(1, 1), listed(id)[0].path("reactions").toList().map { it.path("count").asInt() })
        assertEquals(2, jdbc.queryForObject("select count(*) from comment_reactions where comment_id = ?", Int::class.java, comment))
    }

    @Test
    fun `removing your reaction preserves another member's reaction`() {
        val id = artifact()
        val comment = comment(id)
        react(id, comment)
        react(id, comment, subject = "reaction-bob")

        val result = react(id, comment, reacted = false)

        assertEquals(1, result.path("reactions")[0].path("count").asInt())
        assertFalse(listed(id)[0].path("reactions")[0].path("reacted").asBoolean())
        assertEquals(listOf("reaction-bob"), listed(id)[0].path("reactions")[0].path("users").toList().map { it.asString() })
        assertEquals(1, jdbc.queryForObject("select count(*) from comment_reactions where comment_id = ?", Int::class.java, comment))
    }

    @Test
    fun `removing the last vote deletes the reaction and repeated removal is harmless`() {
        val id = artifact()
        val comment = comment(id)
        react(id, comment)
        react(id, comment, reacted = false)

        val result = react(id, comment, reacted = false)

        assertTrue(result.path("reactions").isEmpty)
        assertTrue(listed(id)[0].path("reactions").isEmpty)
        assertEquals(0, jdbc.queryForObject("select count(*) from comment_reactions where comment_id = ?", Int::class.java, comment))
    }

    @Test
    fun `replies accept reactions without changing comment content timestamps or thread numbers`() {
        val id = artifact()
        val root = comment(id)
        val threadId = listed(id)[0].path("threadId").asLong()
        val reply = json.readTree(mvc.perform(post("/p/$id/threads/$threadId/comments").with(member()).with(csrf())
            .contentType("application/json").content("""{"body":"Reply","version":1}"""))
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        val before = listed(id).toList()

        val result = react(id, reply.path("id").asLong(), "🎉")

        assertEquals(reply.path("id").asLong(), result.path("commentId").asLong())
        val after = listed(id).toList()
        assertEquals(before.map { it.path("body") }, after.map { it.path("body") })
        assertEquals(before.map { it.path("createdAt") }, after.map { it.path("createdAt") })
        assertEquals(before.map { it.path("editedAt") }, after.map { it.path("editedAt") })
        assertEquals(before.map { it.path("threadNumber") }, after.map { it.path("threadNumber") })
        assertTrue(after.single { it.path("id").asLong() == root }.path("reactions").isEmpty)
        assertEquals(1, jdbc.queryForObject("select count(*) from comment_reactions where comment_id = ?", Int::class.java, reply.path("id").asLong()))
    }

    @ParameterizedTest(name = "reject unsupported emoji {0}")
    @ValueSource(strings = ["👍🏽", "not an emoji", "", "👍👍"])
    fun `only default emojis in the bundled catalog can be stored`(emoji: String) {
        val id = artifact()
        val comment = comment(id)

        mvc.perform(put("/p/$id/comments/$comment/reaction").with(member()).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(mapOf("emoji" to emoji, "reacted" to true))))
            .andExpect(status().isBadRequest)

        assertEquals(0, jdbc.queryForObject("select count(*) from comment_reactions where comment_id = ?", Int::class.java, comment))
    }

    @Test
    fun `anonymous public readers can see reactions but cannot add them`() {
        val id = artifact()
        val comment = comment(id)
        react(id, comment)
        pages.setVisibility(id, ArtifactVisibility.PUBLIC, null)

        mvc.perform(put("/p/$id/comments/$comment/reaction").with(csrf()).contentType("application/json")
            .content("""{"emoji":"🎉","reacted":true}"""))
            .andExpect(status().isForbidden)

        val publicComments = json.readTree(mvc.perform(get("/p/$id/comments"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals(1, publicComments[0].path("reactions")[0].path("count").asInt())
        assertFalse(publicComments[0].path("reactions")[0].path("reacted").asBoolean())
        assertEquals(1, jdbc.queryForObject("select count(*) from comment_reactions where comment_id = ?", Int::class.java, comment))
    }

    @Test
    fun `reaction writes require CSRF`() {
        val id = artifact()
        val comment = comment(id)

        mvc.perform(put("/p/$id/comments/$comment/reaction").with(member()).contentType("application/json")
            .content("""{"emoji":"👍","reacted":true}"""))
            .andExpect(status().isForbidden)

        assertEquals(0, jdbc.queryForObject("select count(*) from comment_reactions where comment_id = ?", Int::class.java, comment))
    }

    @Test
    fun `a comment cannot be reacted to through another artifact`() {
        val id = artifact()
        val comment = comment(id)
        val otherId = artifact()

        mvc.perform(put("/p/$otherId/comments/$comment/reaction").with(member()).with(csrf()).contentType("application/json")
            .content("""{"emoji":"👍","reacted":true}"""))
            .andExpect(status().isNotFound)

        assertEquals(0, jdbc.queryForObject("select count(*) from comment_reactions where comment_id = ?", Int::class.java, comment))
    }

    @Test
    fun `deleted comments hide reactions and reject new votes`() {
        val id = artifact()
        val comment = comment(id)
        react(id, comment)
        mvc.perform(delete("/p/$id/comments/$comment").with(member()).with(csrf())).andExpect(status().isNoContent)

        mvc.perform(put("/p/$id/comments/$comment/reaction").with(member()).with(csrf()).contentType("application/json")
            .content("""{"emoji":"🎉","reacted":true}"""))
            .andExpect(status().isNotFound)

        assertTrue(listed(id).isEmpty)
        assertEquals(1, jdbc.queryForObject("select count(*) from comment_reactions where comment_id = ?", Int::class.java, comment))
    }
}
