package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import dev.uncomplex.dumphere.application.HtmlPageStore
import dev.uncomplex.dumphere.application.PageContentFormat
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

    @Autowired private lateinit var pages: HtmlPageStore

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
        assertContains(shell.contentAsString, "sandbox=\"allow-scripts allow-same-origin\"")
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
        assertContains(assertNotNull(content.getHeader("Content-Security-Policy")), "sandbox allow-scripts allow-same-origin;")

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

    @Test
    fun `anonymous viewers can post document comments using the viewer CSRF token and read newest first`() {
        val id = publish("<h1>Comments</h1>")
        setVisibility(id, "public")
        val shell = mvc.perform(get("/p/$id")).andExpect(status().isOk).andReturn()
        val html = shell.response.contentAsString
        assertContains(html, "data-testid=\"${ArtifactViewerIds.COMMENTS}\"")
        assertContains(html, "aria-controls=\"comments-panel\"")
        assertContains(html, "data-testid=\"${ArtifactViewerIds.ELEMENT_SELECTION}\"")
        assertContains(html, "aria-pressed=\"false\"")
        assertContains(html, "<comment-composer")
        assertContains(html, "src=\"/viewer/comment-composer.js\"")
        mvc.perform(get("/viewer/comment-composer.js")).andExpect(status().isOk)
        val token = assertNotNull(Regex("data-csrf=\"([^\"]+)\"").find(html)).groupValues[1]
        val session = assertNotNull(shell.response.getCookie("SESSION"))
        val empty =
            json.readTree(
                mvc
                    .perform(get("/p/$id/comments"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        assertTrue(empty.isArray)
        assertEquals(0, empty.size())

        val body = "<script>alert('plain text')</script>\nSecond line"
        val first =
            json.readTree(
                mvc
                    .perform(
                        post("/p/$id/comments")
                            .cookie(session)
                            .header("X-CSRF-TOKEN", token)
                            .contentType("application/json")
                            .content(json.writeValueAsString(mapOf("body" to body, "version" to 1))),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response.contentAsString,
            )
        assertEquals("Anonymous", first.path("author").asString())
        assertEquals(body, first.path("body").asString())
        val stored =
            jdbc.queryForMap(
                "select c.body, c.author_user_id, v.version, t.xpath from comments c join comment_threads t on t.id = c.thread_id " +
                    "join html_page_versions v on v.id = c.created_in_version_id where c.id = ?",
                first.path("id").asLong(),
            )
        assertEquals(body, stored["body"])
        assertNull(stored["author_user_id"])
        assertNull(stored["xpath"])
        assertEquals(1, stored["version"])

        mvc
            .perform(
                post("/p/$id/comments")
                    .with(member())
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"body":"Newer comment","version":1}"""),
            ).andExpect(status().isCreated)
        val listed =
            json.readTree(
                mvc
                    .perform(get("/p/$id/comments"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        assertTrue(listed.isArray)
        assertEquals(2, listed.size())
        assertEquals("Newer comment", listed[0].path("body").asString())
        assertEquals("Alex Morgan", listed[0].path("author").asString())
        assertEquals(first.path("id").asLong(), listed[1].path("id").asLong())
        assertEquals(2, jdbc.queryForObject("select count(*) from comment_threads where artifact_id = ?", Int::class.java, id))
    }

    @Test
    fun `comments require artifact access and CSRF and reject invalid input without creating empty threads`() {
        val id = publish("<h1>Private comments</h1>")
        val endpoint = "/p/$id/comments"
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized)
        mvc
            .perform(post(endpoint).with(csrf()).contentType("application/json").content("""{"body":"Forbidden","version":1}"""))
            .andExpect(status().isUnauthorized)
        mvc.perform(get(endpoint).with(member("outsider@outside.test"))).andExpect(status().isForbidden)
        mvc.perform(get(endpoint).with(member())).andExpect(status().isOk)
        setVisibility(id, "public")
        mvc
            .perform(post(endpoint).contentType("application/json").content("""{"body":"No CSRF","version":1}"""))
            .andExpect(status().isForbidden)
        for (request in listOf(
            mapOf("body" to "  ", "version" to 1),
            mapOf("body" to "Bad version", "version" to 99),
            mapOf("body" to "x".repeat(10001), "version" to 1),
        )) {
            mvc
                .perform(post(endpoint).with(csrf()).contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest)
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from comment_threads where artifact_id = ?", Int::class.java, id))
        mvc
            .perform(
                post(endpoint)
                    .with(member())
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"body":"Member comment","version":1}"""),
            ).andExpect(status().isCreated)
        setVisibility(id, "internal")
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized)
        mvc
            .perform(post(endpoint).with(csrf()).contentType("application/json").content("""{"body":"Revoked","version":1}"""))
            .andExpect(status().isUnauthorized)
        assertEquals(1, jdbc.queryForObject("select count(*) from comment_threads where artifact_id = ?", Int::class.java, id))
    }

    @Test
    fun `attached threads retain XPath and relative coordinates across updates without changing artifact HTML`() {
        val html = "<html><body><section><h1>Attach here</h1></section></body></html>"
        val id = publish(html)
        setVisibility(id, "public")
        val xpath = "/html[1]/body[1]/section[1]/h1[1]"
        val created =
            json.readTree(
                mvc
                    .perform(
                        post("/p/$id/comments")
                            .with(csrf())
                            .contentType("application/json")
                            .content(
                                json.writeValueAsString(
                                    mapOf(
                                        "body" to "Attached thread",
                                        "version" to 1,
                                        "xpath" to xpath,
                                        "relativeX" to 0.25,
                                        "relativeY" to 0.75,
                                    ),
                                ),
                            ),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response.contentAsString,
            )
        assertEquals(xpath, created.path("xpath").asString())
        assertEquals(0.25, created.path("relativeX").asDouble())
        assertEquals(0.75, created.path("relativeY").asDouble())
        val coordinates =
            jdbc.queryForMap(
                "select relative_x, relative_y from comment_threads where id = ?",
                created.path("threadId").asLong(),
            )
        assertEquals(0.25, coordinates["relative_x"])
        assertEquals(0.75, coordinates["relative_y"])
        assertEquals(
            xpath,
            jdbc.queryForObject("select xpath from comment_threads where id = ?", String::class.java, created.path("threadId").asLong()),
        )
        assertEquals(
            html,
            mvc
                .perform(get("/p/$id/content"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )
        assertEquals(
            html,
            mvc
                .perform(get("/p/$id/download"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )
        mvc
            .perform(
                post("/p/$id/threads/${created.path("threadId").asLong()}/comments")
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"body":"Second message","version":1}"""),
            ).andExpect(status().isCreated)
        mvc
            .perform(
                put("/api/pages/$id")
                    .with(httpBasic("htmlshare", "htmlshare"))
                    .contentType("application/json")
                    .content("""{"html":"<html><body><p>Element removed</p></body></html>"}"""),
            ).andExpect(status().isOk)
        val listed =
            json.readTree(
                mvc
                    .perform(get("/p/$id/comments"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        assertTrue(listed.isArray)
        assertEquals(2, listed.size())
        for (comment in listed) {
            assertEquals(created.path("threadId").asLong(), comment.path("threadId").asLong())
            assertEquals(xpath, comment.path("xpath").asString())
            assertEquals(0.25, comment.path("relativeX").asDouble())
            assertEquals(0.75, comment.path("relativeY").asDouble())
        }
        assertEquals(
            xpath,
            jdbc.queryForObject("select xpath from comment_threads where id = ?", String::class.java, created.path("threadId").asLong()),
        )
        val otherId = publish("<h1>Other artifact</h1>")
        val other =
            json.readTree(
                mvc
                    .perform(get("/p/$otherId/comments").with(member()))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        assertTrue(other.isArray)
        assertEquals(0, other.size())
        mvc
            .perform(
                post("/p/$id/comments")
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"body":"Invalid anchor","version":2,"xpath":"/html[","relativeX":0.5,"relativeY":0.5}"""),
            ).andExpect(status().isBadRequest)
        assertEquals(1, jdbc.queryForObject("select count(*) from comment_threads where artifact_id = ?", Int::class.java, id))
    }

    @Test
    fun `invalid or incomplete relative coordinates do not create threads`() {
        val id = publish("<h1>Coordinates</h1>")
        setVisibility(id, "public")
        for (anchor in listOf(
            mapOf("xpath" to "/html/body/h1", "relativeX" to -0.1, "relativeY" to 0.5),
            mapOf("xpath" to "/html/body/h1", "relativeX" to 0.5, "relativeY" to 1.1),
            mapOf("xpath" to "/html/body/h1", "relativeX" to 0.5),
            mapOf("xpath" to "/html/body/h1"),
            mapOf("relativeX" to 0.5, "relativeY" to 0.5),
        )) {
            val request = mapOf("body" to "Invalid coordinates", "version" to 1) + anchor
            mvc
                .perform(post("/p/$id/comments").with(csrf()).contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest)
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from comment_threads where artifact_id = ?", Int::class.java, id))
    }

    @Test
    fun `threads are ordered by latest comment while messages stay grouped and chronological`() {
        val id = publish("<h1>Thread ordering</h1>")
        setVisibility(id, "public")

        fun add(body: String) =
            json.readTree(
                mvc
                    .perform(
                        post("/p/$id/comments")
                            .with(csrf())
                            .contentType("application/json")
                            .content(json.writeValueAsString(mapOf("body" to body, "version" to 1))),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response.contentAsString,
            )

        val older = add("Older thread, first message")
        val newer = add("Newer thread")
        mvc
            .perform(
                post("/p/$id/threads/${older.path("threadId").asLong()}/comments")
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"body":"Latest reply to older thread","version":1}"""),
            ).andExpect(status().isCreated)

        val listed =
            json.readTree(
                mvc
                    .perform(get("/p/$id/comments"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        assertTrue(listed.isArray)
        assertEquals(3, listed.size())
        assertEquals(
            listOf("Older thread, first message", "Latest reply to older thread", "Newer thread"),
            (0 until listed.size()).map { listed[it].path("body").asString() },
        )
        assertEquals(
            listOf(older.path("threadId").asLong(), older.path("threadId").asLong(), newer.path("threadId").asLong()),
            (0 until listed.size()).map { listed[it].path("threadId").asLong() },
        )
        assertEquals(2, jdbc.queryForObject("select count(*) from comment_threads where artifact_id = ?", Int::class.java, id))
        assertEquals(
            3,
            jdbc.queryForObject(
                "select count(*) from comments c join comment_threads t on t.id = c.thread_id where t.artifact_id = ?",
                Int::class.java,
                id,
            ),
        )
    }

    @Test
    fun `replies preserve thread ownership and record their author and viewed version`() {
        val id = publish("<h1>Original</h1>")
        setVisibility(id, "public")
        val first =
            json.readTree(
                mvc
                    .perform(
                        post("/p/$id/comments")
                            .with(csrf())
                            .contentType("application/json")
                            .content("""{"body":"First message","version":1}"""),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response.contentAsString,
            )
        val threadId = first.path("threadId").asLong()
        mvc
            .perform(
                put("/api/pages/$id")
                    .with(httpBasic("htmlshare", "htmlshare"))
                    .contentType("application/json")
                    .content("""{"html":"<h1>Updated</h1>"}"""),
            ).andExpect(status().isOk)

        val reply =
            json.readTree(
                mvc
                    .perform(
                        post("/p/$id/threads/$threadId/comments")
                            .with(member())
                            .with(csrf())
                            .contentType("application/json")
                            .content("""{"body":"Reply on the newer version","version":2}"""),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response.contentAsString,
            )
        assertEquals(threadId, reply.path("threadId").asLong())
        assertEquals("Alex Morgan", reply.path("author").asString())
        val stored =
            jdbc.queryForMap(
                "select c.body, u.subject, cv.version as comment_version, tv.version as thread_version " +
                    "from comments c join users u on u.id = c.author_user_id " +
                    "join comment_threads t on t.id = c.thread_id " +
                    "join html_page_versions cv on cv.id = c.created_in_version_id " +
                    "join html_page_versions tv on tv.id = t.created_in_version_id where c.id = ?",
                reply.path("id").asLong(),
            )
        assertEquals("Reply on the newer version", stored["body"])
        assertEquals("different-team-member", stored["subject"])
        assertEquals(2, stored["comment_version"])
        assertEquals(1, stored["thread_version"])
        assertEquals(1, jdbc.queryForObject("select count(*) from comment_threads where artifact_id = ?", Int::class.java, id))
    }

    @Test
    fun `replies require access and CSRF and reject invalid input or a thread from another artifact`() {
        val id = publish("<h1>Replies</h1>")
        val first =
            json.readTree(
                mvc
                    .perform(
                        post("/p/$id/comments")
                            .with(member())
                            .with(csrf())
                            .contentType("application/json")
                            .content("""{"body":"First message","version":1}"""),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response.contentAsString,
            )
        val threadId = first.path("threadId").asLong()
        val endpoint = "/p/$id/threads/$threadId/comments"
        val valid = """{"body":"Reply","version":1}"""
        mvc.perform(post(endpoint).with(csrf()).contentType("application/json").content(valid)).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(endpoint)
                    .with(member("outsider@outside.test"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(valid),
            ).andExpect(status().isForbidden)
        setVisibility(id, "public")
        mvc.perform(post(endpoint).contentType("application/json").content(valid)).andExpect(status().isForbidden)
        for (request in listOf(
            mapOf("body" to " ", "version" to 1),
            mapOf("body" to "x".repeat(10001), "version" to 1),
            mapOf("body" to "Reply", "version" to 99),
        )) {
            mvc
                .perform(post(endpoint).with(csrf()).contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest)
        }
        val other = publish("<h1>Other artifact</h1>")
        setVisibility(other, "public")
        mvc
            .perform(post("/p/$other/threads/$threadId/comments").with(csrf()).contentType("application/json").content(valid))
            .andExpect(status().isNotFound)
        mvc
            .perform(post("/p/$id/threads/9223372036854775807/comments").with(csrf()).contentType("application/json").content(valid))
            .andExpect(status().isNotFound)
        assertEquals(1, jdbc.queryForObject("select count(*) from comments where thread_id = ?", Int::class.java, threadId))
    }

    @Test
    fun `your artifacts lists only creations by the signed-in user newest first excluding the current artifact`() {
        val subject = UUID.randomUUID().toString()
        val author = AuthenticatedUser(subject, "alex@example.com", "Alex Morgan")
        val older = pages.publish("Older artifact", "<p>Older</p>", PageContentFormat.HTML, author)
        val newer = pages.publish("Newer artifact", "# Newer", PageContentFormat.MARKDOWN, author)
        val current = pages.publish("Current artifact", "<p>Current</p>", PageContentFormat.HTML, author)
        val other =
            pages.publish(
                "Another user's artifact",
                "<p>Other</p>",
                PageContentFormat.HTML,
                author.copy(subject = UUID.randomUUID().toString()),
            )
        pages.update(older.id, "<p>Updated after the newer artifact</p>", author)
        pages.update(other.id, "<p>Edited but not created by this user</p>", author)

        val response =
            mvc
                .perform(get("/account/artifacts").param("excludeId", current.id).with(member(subject = subject)))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val listed = json.readTree(response.contentAsString)
        assertTrue(listed.isArray)
        assertEquals(2, listed.size())
        assertEquals(listOf(newer.id, older.id), (0 until listed.size()).map { listed[it].path("id").asString() })
        assertEquals("markdown", listed[0].path("format").asString())
        assertEquals("Newer artifact", listed[0].path("title").asString())
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val shell =
            mvc
                .perform(get("/p/${current.id}").with(member(subject = subject)))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertContains(shell, "data-testid=\"${ArtifactViewerIds.ARTIFACTS}\"")
        assertContains(shell, "Your artifacts")
    }

    @Test
    fun `your artifacts requires a team login and returns an empty array for users without publications`() {
        mvc.perform(get("/account/artifacts")).andExpect(status().isUnauthorized)
        mvc.perform(get("/account/artifacts").with(member("outsider@outside.test"))).andExpect(status().isForbidden)
        val response =
            mvc
                .perform(get("/account/artifacts").with(member(subject = UUID.randomUUID().toString())))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val listed = json.readTree(response.contentAsString)
        assertTrue(listed.isArray)
        assertEquals(0, listed.size())
    }

    @Test
    fun `wrapper protects session data and preserves artifact source`() {
        val raw = "<html><body><h1>Original source marker</h1><script>window.artifactScript = true;</script></body></html>"
        val id = publish(raw, "<script>Title</script>")
        setVisibility(id, "public")

        val shell =
            mvc
                .perform(get("/p/$id"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertFalse(shell.contains("Original source marker"))

        val response =
            mvc
                .perform(get("/p/$id/wrapper"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val wrapper = response.contentAsString
        assertContains(wrapper, "&lt;script&gt;Title&lt;/script&gt;")
        assertFalse(wrapper.contains("Original source marker"))
        assertFalse(wrapper.contains("data-csrf"))
        assertFalse(wrapper.contains("comment-composer"))
        assertEquals("no-store", response.getHeader("Cache-Control"))
        for (representation in listOf("content", "download")) {
            assertEquals(
                raw,
                mvc
                    .perform(get("/p/$id/$representation"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        }
        setVisibility(id, "internal")
        mvc.perform(get("/p/$id/wrapper").with(member())).andExpect(status().isOk)
        mvc.perform(get("/p/$id/wrapper").with(member("outsider@outside.test"))).andExpect(status().isForbidden)
        mvc.perform(get("/p/$id/wrapper")).andExpect(status().isFound)
    }

    @Test
    fun `comments record the viewed version rather than the latest artifact version`() {
        val id = publish("<h1>Original</h1>")
        mvc
            .perform(
                put("/api/pages/$id")
                    .with(httpBasic("htmlshare", "htmlshare"))
                    .contentType("application/json")
                    .content("""{"html":"<h1>Updated</h1>"}"""),
            ).andExpect(status().isOk)
        val response =
            json.readTree(
                mvc
                    .perform(
                        post("/p/$id/comments")
                            .with(member())
                            .with(csrf())
                            .contentType("application/json")
                            .content("""{"body":"About the original","version":1}"""),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response.contentAsString,
            )
        val versions =
            jdbc.queryForMap(
                "select t.created_in_version_id as thread_version, c.created_in_version_id as comment_version, v.id as expected_version " +
                    "from comments c join comment_threads t on t.id = c.thread_id " +
                    "join html_page_versions v on v.page_id = t.artifact_id and v.version = 1 where c.id = ?",
                response.path("id").asLong(),
            )
        assertEquals(versions["expected_version"], versions["thread_version"])
        assertEquals(versions["expected_version"], versions["comment_version"])
        assertEquals(2, committedVersion(id))
    }

    private fun assertInternalRequiresLogin(id: String) {
        for (path in listOf("", "/wrapper", "/content", "/download")) {
            val response =
                mvc
                    .perform(get("/p/$id$path"))
                    .andExpect(status().isFound)
                    .andReturn()
                    .response
            val location = URI.create(assertNotNull(response.redirectedUrl))
            assertEquals("/login", location.path)
            assertEquals("/p/$id$path", URLDecoder.decode(location.rawQuery.substringAfter("redirectUrl="), Charsets.UTF_8))
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

    private fun member(
        email: String = "alex@example.com",
        subject: String = "different-team-member",
    ) = oidcLogin()
        .idToken {
            it
                .subject(
                    subject,
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
