package dev.uncomplex.dumphere.adapters.authentication

import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.PageContentFormat
import dev.uncomplex.dumphere.application.UserProvisioningService
import dev.uncomplex.dumphere.ports.PublishedArtifactsStore
import dev.uncomplex.dumphere.testing.PostgresTestDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest(properties = [
    "spring.security.oauth2.client.registration.google.client-id=test-client",
    "spring.security.oauth2.client.registration.google.client-secret=test-secret",
    "htmlshare.allowed-email-domain=example.com",
])
@AutoConfigureMockMvc
class AuthenticatedUsersIntegrationTests {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var store: PublishedArtifactsStore
    @Autowired private lateinit var provisioning: UserProvisioningService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var events: ApplicationEventPublisher
    @Autowired private lateinit var encoder: JwtEncoder
    @Autowired private lateinit var json: ObjectMapper

    companion object {
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = PostgresTestDatabase.configure(registry)
    }

    private fun artifact() = store.publish("Read-only comments", "# Review", PageContentFormat.MARKDOWN, null).id

    private fun snapshot(subject: String) = jdbc.queryForMap(
        "select id, email, display_name, created_at, updated_at, xmin::text as revision from users where subject = ?", subject,
    )

    private fun googleAuthentication(subject: String, name: String): OAuth2AuthenticationToken {
        val now = Instant.now()
        val token = OidcIdToken("test-google-token", now, now.plusSeconds(120),
            mapOf("sub" to subject, "email" to "$subject@example.com", "name" to name))
        return OAuth2AuthenticationToken(DefaultOidcUser(emptyList(), token), emptyList(), "google")
    }

    @Test
    fun `comments polling resolves the persisted user without saving or applying session claims`() {
        val subject = UUID.randomUUID().toString()
        provisioning.provision(AuthenticatedUser(subject, "original@example.com", "Original name"))
        val id = artifact()
        val before = snapshot(subject)
        val session = oidcLogin().idToken { it.subject(subject).claim("email", "different@example.com").claim("name", "Different name") }

        repeat(3) {
            mvc.perform(get("/p/$id/comments").with(session)).andExpect(status().isOk)
        }

        // xmin changes on every PostgreSQL row update, even if the assigned values happen to be identical.
        assertEquals(before, snapshot(subject))
    }

    @Test
    fun `a session without a provisioned user fails instead of creating a user on GET`() {
        val subject = UUID.randomUUID().toString()
        val id = artifact()

        mvc.perform(get("/p/$id/comments").with(oidcLogin().idToken {
            it.subject(subject).claim("email", "$subject@example.com")
        })).andExpect(status().isUnauthorized)

        assertEquals(0, jdbc.queryForObject("select count(*) from users where subject = ?", Int::class.java, subject))
    }

    @Test
    fun `successful Google authentication provisions a user before controller resolution`() {
        val subject = UUID.randomUUID().toString()
        val authentication = googleAuthentication(subject, "Google member")

        events.publishEvent(AuthenticationSuccessEvent(authentication))

        assertEquals("Google member", snapshot(subject)["display_name"])
        val id = artifact()
        mvc.perform(get("/p/$id/comments").with(oidcLogin().oidcUser(authentication.principal as DefaultOidcUser)))
            .andExpect(status().isOk)
    }

    @Test
    fun `repeated successful authentication with an unchanged profile does not update the user row`() {
        val subject = UUID.randomUUID().toString()
        val authentication = googleAuthentication(subject, "Google member")
        events.publishEvent(AuthenticationSuccessEvent(authentication))
        val before = snapshot(subject)

        events.publishEvent(AuthenticationSuccessEvent(authentication))

        assertEquals(before, snapshot(subject))
    }

    @Test
    fun `successful authentication saves a changed profile without creating another user`() {
        val subject = UUID.randomUUID().toString()
        events.publishEvent(AuthenticationSuccessEvent(googleAuthentication(subject, "Original name")))
        val before = snapshot(subject)

        events.publishEvent(AuthenticationSuccessEvent(googleAuthentication(subject, "Updated name")))

        val after = snapshot(subject)
        assertEquals(before["id"], after["id"])
        assertEquals("Updated name", after["display_name"])
        assertNotEquals(before["revision"], after["revision"])
        assertNotEquals(before["updated_at"], after["updated_at"])
    }

    @Test
    fun `real bearer authentication provisions once and repeated polling does not write`() {
        val subject = UUID.randomUUID().toString()
        val now = Instant.now()
        val token = encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder().subject(subject)
            .claim("email", "$subject@example.com").claim("name", "Bearer member")
            .issuedAt(now).expiresAt(now.plusSeconds(120)).build())).tokenValue
        val id = artifact()
        mvc.perform(get("/p/$id/comments").header("Authorization", "Bearer $token")).andExpect(status().isOk)
        val before = snapshot(subject)

        repeat(3) {
            mvc.perform(get("/p/$id/comments").header("Authorization", "Bearer $token")).andExpect(status().isOk)
        }

        assertEquals("Bearer member", before["display_name"])
        assertEquals(before, snapshot(subject))
    }

    @Test
    fun `authentication from a disallowed Google domain does not provision a user`() {
        val subject = UUID.randomUUID().toString()
        val now = Instant.now()
        val token = OidcIdToken("test-google-token", now, now.plusSeconds(120),
            mapOf("sub" to subject, "email" to "outsider@other.example", "name" to "Outsider"))
        val authentication = OAuth2AuthenticationToken(DefaultOidcUser(emptyList(), token), emptyList(), "google")

        events.publishEvent(AuthenticationSuccessEvent(authentication))

        assertEquals(0, jdbc.queryForObject("select count(*) from users where subject = ?", Int::class.java, subject))
    }

    @Test
    fun `failed bearer authentication does not provision a user`() {
        val subject = UUID.randomUUID().toString()
        val now = Instant.now()
        val token = encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder().subject(subject)
            .claim("email", "$subject@example.com").issuedAt(now.minusSeconds(3600))
            .expiresAt(now.minusSeconds(120)).build())).tokenValue
        val id = artifact()

        mvc.perform(get("/p/$id/comments").header("Authorization", "Bearer $token")).andExpect(status().isUnauthorized)

        assertEquals(0, jdbc.queryForObject("select count(*) from users where subject = ?", Int::class.java, subject))
    }

    @Test
    fun `real Basic authentication provisions the API actor before publication`() {
        val response = mvc.perform(post("/api/pages").with(httpBasic("htmlshare", "htmlshare"))
            .contentType("application/json").content("""{"title":"Basic auth","content":"# Test","mimeType":"text/markdown"}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val id = json.readTree(response).path("url").asString().substringAfterLast('/')
        val before = snapshot("htmlshare")

        mvc.perform(get("/api/pages/$id").with(httpBasic("htmlshare", "htmlshare"))).andExpect(status().isOk)

        assertEquals(before, snapshot("htmlshare"))
        assertEquals("htmlshare", jdbc.queryForObject(
            "select u.subject from html_pages p join users u on u.id = p.created_by_user_id where p.id = ?", String::class.java, id))
        assertTrue(id.isNotBlank())
    }
}
