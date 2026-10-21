package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import dev.uncomplex.dumphere.testing.PostgresTestDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.security.oauth2.client.registration.google.client-id=test-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-secret",
        "spring.security.oauth2.client.registration.google.scope=openid,email,profile",
        "htmlshare.allowed-email-domain=example.com",
        "spring.ai.mcp.server.protocol=STREAMABLE",
    ],
)
@AutoConfigureMockMvc
class ArtifactSharingIntegrationTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var json: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var properties: DumpHereApplicationProperties

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = PostgresTestDatabase.configure(registry)
    }

    @Test
    fun legacyMetadataAndContentAreReadableWithDefaultKotlinFieldsAndJavaTime() {
        val id = UUID.randomUUID().toString()
        val directory = Files.createDirectories(Path.of(properties.storageDir))
        val metadata = directory.resolve("$id.json")
        val content = directory.resolve("$id.html")
        val raw = "<h1>Legacy café</h1>"
        Files.writeString(
            metadata,
            """{"id":"$id","title":"Legacy café","url":"http://localhost:7331/p/$id","createdAt":"2026-01-02T03:04:05Z","bytes":${raw.toByteArray().size},"isLive":true}""",
        )
        Files.writeString(content, raw)

        try {
            val response =
                mvc
                    .perform(get("/api/pages/$id").with(httpBasic("htmlshare", "htmlshare")))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
            val page = json.readTree(response.contentAsString)
            assertEquals(id, page.path("id").asString())
            assertEquals("2026-01-02T03:04:05Z", page.path("createdAt").asString())
            assertEquals(1, page.path("version").asInt())
            assertEquals("internal", page.path("visibility").asString())
            assertTrue(page.required("live").booleanValue())
            assertFalse(page.has("isLive"))

            val download =
                mvc
                    .perform(get("/p/$id/download").with(member()))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
            assertTrue(raw.toByteArray().contentEquals(download.contentAsByteArray))
            assertEquals(0, versionCount(id))
        } finally {
            Files.deleteIfExists(metadata)
            Files.deleteIfExists(content)
        }
    }

    @Test
    fun internalByDefaultAndAnonymousReadersEnterLoginFlow() {
        val id = publish("<h1>Internal secret</h1>")

        assertNull(deadline(id))
        assertInternalRequiresLogin(id)
        mvc.perform(get("/api/pages/$id")).andExpect(status().isUnauthorized)
    }

    @Test
    fun internalArtifactLoginPreservesTheReturnUrlAndResumesForAuthenticatedMembers() {
        val id = publish("<h1>Internal secret</h1>")
        val target = "/p/$id?view=full&section=release"
        val redirect =
            mvc
                .perform(get(target))
                .andExpect(status().isFound)
                .andReturn()
                .response
        val login = assertNotNull(redirect.redirectedUrl)
        assertTrue(login.startsWith("/login?redirectUrl="))
        assertEquals(target, URLDecoder.decode(login.substringAfter("redirectUrl="), Charsets.UTF_8))
        assertFalse(redirect.contentAsString.contains("Internal secret"))

        val loginPage =
            mvc
                .perform(get(URI.create(login)))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertContains(loginPage, "Continue with Google")
        assertContains(loginPage, "/oauth2/authorization/google?redirectUrl=")
        val resumed =
            mvc
                .perform(get(URI.create(login)).with(member()))
                .andExpect(status().isFound)
                .andReturn()
                .response
        assertEquals(target, resumed.redirectedUrl)
        mvc.perform(get(target).with(member())).andExpect(status().isOk)
        mvc.perform(get("/p/$id/content").with(member())).andExpect(status().isOk)
        assertNull(deadline(id))
        assertEquals(1, versionCount(id))
    }

    @Test
    fun anAuthenticatedAccountOutsideTheAllowedDomainIsForbiddenRatherThanSentBackToLogin() {
        val id = publish("<h1>Internal secret</h1>")
        for (path in listOf("", "/content", "/download", "/status")) {
            val response =
                mvc
                    .perform(get("/p/$id$path").with(member("outsider@outside.test")))
                    .andExpect(status().isForbidden)
                    .andReturn()
                    .response
            assertNull(response.redirectedUrl)
            assertFalse(response.contentAsString.contains("Internal secret"))
        }
    }

    @Test
    fun publicHtmlHasSafeViewerAndExactRawDownloadAndCanBeRevoked() {
        val raw = "<!doctype html><h1>Interactive café</h1>\r\n<script>document.body.dataset.working='yes'</script>"
        val id = publish(raw, "café.html")
        val before = Instant.now()

        val shared = setVisibility(id, "public")
        assertEquals("public", shared.path("visibility").asString())
        val until = assertNotNull(deadline(id))
        assertTrue(Duration.between(before, until).toHours() in 719..720)
        assertEquals(1, committedVersion(id))
        assertEquals(1, versionCount(id))

        val shell =
            mvc
                .perform(get("/p/$id"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertContains(shell.contentAsString, "sandbox=\"allow-scripts\"")
        assertContains(shell.contentAsString, "data-can-manage=\"false\"")
        assertNull(shell.redirectedUrl)
        assertFalse(shell.contentAsString.contains("<h1>Interactive café"))
        assertFalse(shell.contentAsString.contains("Sign in"))
        assertEquals("no-store", shell.getHeader("Cache-Control"))
        assertContains(assertNotNull(shell.getHeader("Content-Security-Policy")), "script-src 'self'")

        val content =
            mvc
                .perform(get("/p/$id/content"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertEquals(raw, content.getContentAsString(Charsets.UTF_8))
        assertContains(assertNotNull(content.getHeader("Content-Security-Policy")), "sandbox allow-scripts;")
        assertFalse(assertNotNull(content.getHeader("Content-Security-Policy")).contains("allow-same-origin"))

        val download =
            mvc
                .perform(get("/p/$id/download"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertTrue(raw.toByteArray().contentEquals(download.contentAsByteArray))
        assertContains(assertNotNull(download.getHeader("Content-Disposition")), "attachment;")
        assertContains(assertNotNull(download.getHeader("Content-Disposition")), ".html")
        assertEquals("no-store", download.getHeader("Cache-Control"))

        setVisibility(id, "internal")
        assertNull(deadline(id))
        assertInternalRequiresLogin(id)
        assertEquals(1, versionCount(id))
    }

    @Test
    fun defaultContentCspAllowsBellhopDependenciesWithoutRelaxingTheViewerOrSandbox() {
        val id = publish("<h1>Bellhop artifact</h1>")
        setVisibility(id, "public")

        val content = mvc.perform(get("/p/$id/content")).andExpect(status().isOk).andReturn().response
        assertEquals(
            "sandbox allow-scripts; default-src 'none'; img-src data: https:; " +
                "style-src 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com; " +
                "script-src 'unsafe-inline' https://cdn.jsdelivr.net; font-src https://fonts.gstatic.com; " +
                "connect-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'",
            content.getHeader("Content-Security-Policy"),
        )
        val shell = mvc.perform(get("/p/$id")).andExpect(status().isOk).andReturn().response
        assertEquals(
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' https:; " +
                "frame-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'",
            shell.getHeader("Content-Security-Policy"),
        )
        assertContains(shell.contentAsString, "sandbox=\"allow-scripts\"")
        assertFalse(shell.contentAsString.contains("allow-same-origin"))
    }

    @Test
    fun markdownDownloadPreservesSourceAndRenderedContentIsSeparate() {
        val raw = "# Notes\r\n\r\n- [x] Ready\r\n\r\n<script>alert('not executable')</script>"
        val id = publish(raw, "release.md", "text/markdown")
        setVisibility(id, "public")

        val shell =
            mvc
                .perform(get("/p/$id"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertContains(shell.contentAsString, "href=\"#icon-markdown\"")
        val content =
            mvc
                .perform(get("/p/$id/content"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertContains(content.contentAsString, "<h1")
        assertContains(content.contentAsString, "&lt;script&gt;")
        val download =
            mvc
                .perform(get("/p/$id/download"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertTrue(raw.toByteArray().contentEquals(download.contentAsByteArray))
        assertContains(assertNotNull(download.getHeader("Content-Disposition")), "release.md")
        assertContains(assertNotNull(download.contentType), "text/markdown")
    }

    @Test
    fun readsEditsLiveToggleAndRepeatedPublicRequestsNeverExtendDeadline() {
        val id = publish("<p>first</p>")
        setVisibility(id, "public")
        val until = deadline(id)

        setVisibility(id, "public")
        assertEquals(until, deadline(id))
        mvc.perform(get("/p/$id/status")).andExpect(status().isOk)
        mvc.perform(get("/p/$id/download")).andExpect(status().isOk)
        assertEquals(until, deadline(id))
        mvc
            .perform(
                put(
                    "/api/pages/$id",
                ).with(httpBasic("htmlshare", "htmlshare")).contentType("application/json").content("""{"html":"<p>second</p>"}"""),
            ).andExpect(status().isOk)
        assertEquals(2, committedVersion(id))
        assertEquals("<p>second</p>", committedContents(id))
        assertEquals(until, deadline(id))
        mvc
            .perform(
                post(
                    "/api/agent/pages/$id/edit",
                ).with(
                    httpBasic("htmlshare", "htmlshare"),
                ).contentType("application/json")
                    .content("""{"oldString":"second","newString":"third"}"""),
            ).andExpect(status().isOk)
        assertEquals(3, committedVersion(id))
        assertEquals("<p>third</p>", committedContents(id))
        assertEquals(until, deadline(id))
        mvc
            .perform(
                post(
                    "/api/pages/$id/live",
                ).with(httpBasic("htmlshare", "htmlshare")).contentType("application/json").content("""{"live":true}"""),
            ).andExpect(status().isOk)
        assertEquals(true, jdbc.queryForObject("select is_live from html_pages where id = ?", Boolean::class.java, id))
        assertEquals(until, deadline(id))
        assertEquals(3, versionCount(id))
        val state =
            mvc
                .perform(get("/p/$id/status"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertTrue(
            json
                .readTree(state)
                .path("artifact")
                .path("live")
                .asBoolean(),
            state,
        )
    }

    @Test
    fun expiryBlocksAllAnonymousAccessWithoutCleanupAndAllowsExplicitResharing() {
        val id = publish("<h1>Expired secret</h1>")
        setVisibility(id, "public")
        val expiredAt = Instant.now().minusSeconds(1)
        jdbc.update("update html_pages set public_until = ? where id = ?", Timestamp.from(expiredAt), id)

        val metadata =
            mvc
                .perform(
                    get("/api/pages/$id").with(httpBasic("htmlshare", "htmlshare")),
                ).andExpect(status().isOk)
                .andReturn()
                .response
        assertEquals("internal", json.readTree(metadata.contentAsString).path("visibility").asString())
        assertInternalRequiresLogin(id)
        assertTrue(assertNotNull(deadline(id)).isBefore(Instant.now()))
        mvc.perform(get("/p/$id/content").with(member())).andExpect(status().isOk)

        setVisibility(id, "public")
        assertTrue(assertNotNull(deadline(id)).isAfter(Instant.now().plus(Duration.ofDays(29))))
        mvc.perform(get("/p/$id/content")).andExpect(status().isOk)
        assertEquals(1, versionCount(id))
    }

    @Test
    fun anyTeamMemberCanChangeVisibilityThroughCsrfProtectedBrowserEndpoint() {
        val id = publish("<h1>Owned by API identity</h1>")
        val shell =
            mvc
                .perform(get("/p/$id").with(member()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertContains(shell, "data-can-manage=\"true\"")
        assertContains(shell, "data-testid=\"${ArtifactViewerIds.SHARE}\"")
        assertContains(shell, "Alex Morgan")
        assertContains(shell, "https://example.com/avatar.png")

        mvc
            .perform(post("/p/$id/visibility").with(member()).contentType("application/json").content("""{"visibility":"public"}"""))
            .andExpect(status().isForbidden)
        assertNull(deadline(id))
        mvc
            .perform(
                post("/p/$id/visibility")
                    .with(member())
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"visibility":"public"}"""),
            ).andExpect(status().isOk)
        assertNotNull(deadline(id))
        val updater =
            jdbc.queryForObject(
                "select u.subject from users u join html_pages p on p.updated_by_user_id = u.id where p.id = ?",
                String::class.java,
                id,
            )
        assertEquals("different-team-member", updater)

        mvc
            .perform(post("/p/$id/visibility").with(csrf()).contentType("application/json").content("""{"visibility":"internal"}"""))
            .andExpect(status().isForbidden)
        assertNotNull(deadline(id))
        val outside = member("outsider@outside.test")
        val publicShell =
            mvc
                .perform(get("/p/$id").with(outside))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertContains(publicShell, "data-can-manage=\"false\"")
        mvc
            .perform(
                post("/p/$id/visibility")
                    .with(outside)
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"visibility":"internal"}"""),
            ).andExpect(status().isForbidden)
        assertNotNull(deadline(id))

        mvc
            .perform(
                post("/p/$id/visibility")
                    .with(member())
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"visibility":"internal"}"""),
            ).andExpect(status().isOk)
        assertNull(deadline(id))
        assertEquals(1, versionCount(id))
    }

    @Test
    fun sharingRejectsUnknownVisibilityAndDoesNotExposeManagementToAnonymousUsers() {
        val id = publish("<h1>Test</h1>")
        mvc
            .perform(
                post(
                    "/api/pages/$id/visibility",
                ).with(httpBasic("htmlshare", "htmlshare")).contentType("application/json").content("""{"visibility":"forever"}"""),
            ).andExpect(status().isBadRequest)
        assertNull(deadline(id))
        mvc
            .perform(post("/api/pages/$id/visibility").contentType("application/json").content("""{"visibility":"public"}"""))
            .andExpect(status().isUnauthorized)
        assertNull(deadline(id))
    }

    @Test
    fun titlesCannotInjectViewerMarkupOrDownloadHeaders() {
        val id = publish("<p>original</p>", "../<script>parent.pwned=true</script>\"\r\n.html")
        setVisibility(id, "public")

        val shell =
            mvc
                .perform(get("/p/$id"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertContains(shell, "&lt;script&gt;parent.pwned=true&lt;/script&gt;")
        assertFalse(shell.contains("<script>parent.pwned=true</script>"))
        val download =
            mvc
                .perform(get("/p/$id/download"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val disposition = assertNotNull(download.getHeader("Content-Disposition"))
        assertFalse(disposition.contains('\r'))
        assertFalse(disposition.contains('\n'))
        assertFalse(disposition.contains("../"))
        assertContains(disposition, ".html")
        assertEquals("<p>original</p>", download.getContentAsString(Charsets.UTF_8))
    }

    private fun assertInternalRequiresLogin(id: String) {
        for (path in listOf("", "/content", "/download")) {
            val response =
                mvc
                    .perform(get("/p/$id$path"))
                    .andExpect(status().isFound)
                    .andReturn()
                    .response
            assertEquals(RedirectUrlSupport.loginUrl("/p/$id$path"), response.redirectedUrl)
            assertTrue(response.contentAsString.isEmpty())
        }
        val state =
            mvc
                .perform(get("/p/$id/status"))
                .andExpect(status().isUnauthorized)
                .andReturn()
                .response
        assertNull(state.redirectedUrl)
    }

    private fun member(email: String = "alex@example.com") =
        oidcLogin()
            .idToken {
                it
                    .subject(
                        "different-team-member",
                    ).claim("email", email)
                    .claim("name", "Alex Morgan")
                    .claim("picture", "https://example.com/avatar.png")
            }

    private fun publish(
        contents: String,
        title: String = "test.html",
        mime: String = "text/html",
    ): String {
        val response =
            mvc
                .perform(
                    post("/api/pages")
                        .with(httpBasic("htmlshare", "htmlshare"))
                        .contentType("application/json")
                        .content(
                            json.writeValueAsString(
                                mapOf(
                                    "title" to title,
                                    "content" to contents,
                                    "mimeType" to mime,
                                ),
                            ),
                        ),
                ).andExpect(status().isOk)
                .andReturn()
                .response
        val url = json.readTree(response.contentAsString).path("url").asString()
        assertTrue(url.contains("/p/"))
        return url.substringAfterLast('/')
    }

    private fun setVisibility(
        id: String,
        visibility: String,
    ) = json.readTree(
        mvc
            .perform(
                post("/api/pages/$id/visibility")
                    .with(httpBasic("htmlshare", "htmlshare"))
                    .contentType("application/json")
                    .content(json.writeValueAsString(mapOf("visibility" to visibility))),
            ).andExpect(status().isOk)
            .andReturn()
            .response.contentAsString,
    )

    private fun deadline(id: String): Instant? =
        jdbc.queryForObject("select public_until from html_pages where id = ?", Timestamp::class.java, id)?.toInstant()

    private fun committedVersion(id: String) =
        jdbc.queryForObject("select current_version from html_pages where id = ?", Int::class.java, id)

    private fun versionCount(id: String) =
        jdbc.queryForObject("select count(*) from html_page_versions where page_id = ?", Int::class.java, id)

    private fun committedContents(id: String) =
        jdbc.queryForObject(
            "select v.html from html_page_versions v join html_pages p on p.id = v.page_id and p.current_version = v.version where p.id = ?",
            String::class.java,
            id,
        )
}
