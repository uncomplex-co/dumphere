package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.ArtifactVisibility
import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.PageContentFormat
import java.net.URI
import java.net.URLDecoder
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ArtifactSharingIntegrationTests : CommentMutationTestSupport() {
    @Test
    fun internalByDefaultAndAnonymousReadersEnterLoginFlow() {
        // Arrange
        val id = publish("<h1>Internal secret</h1>")

        // Act & Assert
        assertNull(deadline(id))
        assertInternalRequiresLogin(id)
        mvc.perform(get("/api/pages/$id")).andExpect(status().isUnauthorized)
    }

    @Test
    fun internalArtifactLoginPreservesTheReturnUrlAndResumesForAuthenticatedMembers() {
        // Arrange
        val id = publish("<h1>Internal secret</h1>")
        val target = "/p/$id?view=full&section=release"
        // Act & Assert
        val redirect = mvc.perform(get(target)).andExpect(status().isFound).andReturn().response
        val login = assertNotNull(redirect.redirectedUrl)
        assertTrue(login.startsWith("/login?redirectUrl="))
        assertEquals(
            target,
            URLDecoder.decode(login.substringAfter("redirectUrl="), Charsets.UTF_8),
        )
        assertFalse(redirect.contentAsString.contains("Internal secret"))

        val loginPage =
            mvc.perform(get(URI.create(login))).andExpect(status().isOk).andReturn().response.contentAsString
        assertContains(loginPage, "Continue with Google")
        assertContains(loginPage, "/oauth2/authorization/google?redirectUrl=")
        val resumed =
            mvc.perform(get(URI.create(login)).with(member())).andExpect(status().isFound).andReturn().response
        assertEquals(target, resumed.redirectedUrl)
        mvc.perform(get(target).with(member())).andExpect(status().isOk)
        mvc.perform(get("/p/$id/content").with(member())).andExpect(status().isOk)
        assertNull(deadline(id))
        assertEquals(1, versionCount(id))
    }

    @Test
    fun anAuthenticatedAccountOutsideTheAllowedDomainIsForbiddenRatherThanSentBackToLogin() {
        // Arrange
        val id = publish("<h1>Internal secret</h1>")
        // Act & Assert
        for (path in listOf("", "/content", "/download", "/status")) {
            val response =
                mvc.perform(get("/p/$id$path").with(member("outsider@outside.test")))
                    .andExpect(status().isForbidden)
                    .andReturn()
                    .response
            assertNull(response.redirectedUrl)
            assertFalse(response.contentAsString.contains("Internal secret"))
        }
    }

    @Test
    fun publicHtmlHasSafeViewerAndExactRawDownloadAndCanBeRevoked() {
        // Arrange
        val raw = "<!doctype html><h1>Interactive café</h1>\r\n<script>document.body.dataset.working='yes'</script>"
        val id = publish(raw, "café.html")
        val before = Instant.now()

        // Act & Assert
        val shared = setVisibility(id, "public")
        assertEquals("public", shared.path("visibility").path("visibility").asString())
        val until = assertNotNull(deadline(id))
        assertTrue(Duration.between(before, until).toHours() in 719..720)
        assertEquals(1, committedVersion(id))
        assertEquals(1, versionCount(id))

        val shell = mvc.perform(get("/p/$id")).andExpect(status().isOk).andReturn().response
        assertContains(shell.contentAsString, "sandbox=\"allow-scripts allow-same-origin\"")
        assertContains(shell.contentAsString, "data-can-manage=\"false\"")
        assertNull(shell.redirectedUrl)
        assertFalse(shell.contentAsString.contains("<h1>Interactive café"))
        assertFalse(shell.contentAsString.contains("Sign in"))
        assertEquals("no-store", shell.getHeader("Cache-Control"))
        assertContains(
            assertNotNull(shell.getHeader("Content-Security-Policy")),
            "script-src 'self'",
        )

        val content = mvc.perform(get("/p/$id/content")).andExpect(status().isOk).andReturn().response
        assertEquals(raw, content.getContentAsString(Charsets.UTF_8))
        assertContains(
            assertNotNull(content.getHeader("Content-Security-Policy")),
            "sandbox allow-scripts allow-same-origin;",
        )

        val download = mvc.perform(get("/p/$id/download")).andExpect(status().isOk).andReturn().response
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
        // Arrange
        val raw = "# Notes\r\n\r\n- [x] Ready\r\n\r\n<script>alert('not executable')</script>"
        val id = publish(raw, "release.md", "text/markdown")
        setVisibility(id, "public")

        // Act & Assert
        val shell = mvc.perform(get("/p/$id")).andExpect(status().isOk).andReturn().response
        assertContains(shell.contentAsString, "href=\"#icon-markdown\"")
        val content = mvc.perform(get("/p/$id/content")).andExpect(status().isOk).andReturn().response
        assertContains(content.contentAsString, "<h1")
        assertContains(content.contentAsString, "&lt;script&gt;")
        val download = mvc.perform(get("/p/$id/download")).andExpect(status().isOk).andReturn().response
        assertTrue(raw.toByteArray().contentEquals(download.contentAsByteArray))
        assertContains(assertNotNull(download.getHeader("Content-Disposition")), "release.md")
        assertContains(assertNotNull(download.contentType), "text/markdown")
    }

    @Test
    fun readsEditsLiveToggleAndRepeatedPublicRequestsNeverExtendDeadline() {
        // Arrange
        val id = publish("<p>first</p>")
        setVisibility(id, "public")
        val until = deadline(id)

        // Act & Assert
        setVisibility(id, "public")
        assertEquals(until, deadline(id))
        mvc.perform(get("/p/$id/status")).andExpect(status().isOk)
        mvc.perform(get("/p/$id/download")).andExpect(status().isOk)
        assertEquals(until, deadline(id))
        mvc.perform(
                post("/api/pages/$id/replace")
                    .with(httpBasic("htmlshare", "htmlshare"))
                    .contentType("text/html; charset=utf-8")
                    .content("<p>second</p>")
            )
            .andExpect(status().isOk)
        assertEquals(2, committedVersion(id))
        assertEquals("<p>second</p>", committedContents(id))
        assertEquals(until, deadline(id))
        mvc.perform(
                post("/api/agent/pages/$id/edit")
                    .with(httpBasic("htmlshare", "htmlshare"))
                    .contentType("application/json")
                    .content("""{"oldString":"second","newString":"third"}""")
            )
            .andExpect(status().isOk)
        assertEquals(3, committedVersion(id))
        assertEquals("<p>third</p>", committedContents(id))
        assertEquals(until, deadline(id))
        mvc.perform(
                post("/api/pages/$id/live")
                    .with(httpBasic("htmlshare", "htmlshare"))
                    .contentType("application/json")
                    .content("""{"live":true}""")
            )
            .andExpect(status().isOk)
        assertEquals(
            true,
            jdbc.queryForObject(
                "select is_live from html_pages where id = ?",
                Boolean::class.java,
                id,
            ),
        )
        assertEquals(until, deadline(id))
        assertEquals(3, versionCount(id))
        val state = mvc.perform(get("/p/$id/status")).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(
            json.readTree(state).path("artifact").path("live").asBoolean(),
            state,
        )
    }

    @Test
    fun expiryBlocksAllAnonymousAccessWithoutCleanupAndAllowsExplicitResharing() {
        // Arrange
        val id = publish("<h1>Expired secret</h1>")
        setVisibility(id, "public")
        val expiredAt = Instant.now().minusSeconds(1)
        jdbc.update(
            "update html_pages set public_until = ? where id = ?",
            Timestamp.from(expiredAt),
            id,
        )

        // Act & Assert
        val metadata =
            mvc.perform(get("/api/pages/$id").with(httpBasic("htmlshare", "htmlshare")))
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertEquals(
            "internal",
            json.readTree(metadata.contentAsString).path("visibility").path("visibility").asString(),
        )
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
        // Arrange
        val id = publish("<h1>Owned by API identity</h1>")
        // Act & Assert
        val shell =
            mvc.perform(get("/p/$id").with(member())).andExpect(status().isOk).andReturn().response.contentAsString
        assertContains(shell, "data-can-manage=\"true\"")
        assertContains(shell, "data-testid=\"${ArtifactViewerIds.SHARE}\"")
        assertContains(shell, "Alex Morgan")
        assertContains(shell, "https://example.com/avatar.png")

        mvc.perform(
                post("/p/$id/visibility")
                    .with(member())
                    .contentType("application/json")
                    .content("""{"visibility":"public"}""")
            )
            .andExpect(status().isForbidden)
        assertNull(deadline(id))
        mvc.perform(
                post("/p/$id/visibility")
                    .with(member())
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"visibility":"public"}""")
            )
            .andExpect(status().isOk)
        assertNotNull(deadline(id))
        val updater =
            jdbc.queryForObject(
                "select u.subject from users u join html_pages p on p.updated_by_user_id = u.id where p.id = ?",
                String::class.java,
                id,
            )
        assertEquals("different-team-member", updater)

        mvc.perform(
                post("/p/$id/visibility")
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"visibility":"internal"}""")
            )
            .andExpect(status().isForbidden)
        assertNotNull(deadline(id))
        val outside = member("outsider@outside.test")
        val publicShell =
            mvc.perform(get("/p/$id").with(outside)).andExpect(status().isOk).andReturn().response.contentAsString
        assertContains(publicShell, "data-can-manage=\"false\"")
        mvc.perform(
                post("/p/$id/visibility")
                    .with(outside)
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"visibility":"internal"}""")
            )
            .andExpect(status().isForbidden)
        assertNotNull(deadline(id))

        mvc.perform(
                post("/p/$id/visibility")
                    .with(member())
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"visibility":"internal"}""")
            )
            .andExpect(status().isOk)
        assertNull(deadline(id))
        assertEquals(1, versionCount(id))
    }

    @Test
    fun sharingRejectsUnknownVisibilityAndDoesNotExposeManagementToAnonymousUsers() {
        // Arrange
        val id = publish("<h1>Test</h1>")
        // Act & Assert
        mvc.perform(
                post("/api/pages/$id/visibility")
                    .with(httpBasic("htmlshare", "htmlshare"))
                    .contentType("application/json")
                    .content("""{"visibility":"forever"}""")
            )
            .andExpect(status().isBadRequest)
        assertNull(deadline(id))
        mvc.perform(
                post("/api/pages/$id/visibility").contentType("application/json").content("""{"visibility":"public"}""")
            )
            .andExpect(status().isUnauthorized)
        assertNull(deadline(id))
    }

    @Test
    fun titlesCannotInjectViewerMarkupOrDownloadHeaders() {
        // Arrange
        val id = publish("<p>original</p>", "../<script>parent.pwned=true</script>\"\r\n.html")
        setVisibility(id, "public")

        // Act & Assert
        val shell = mvc.perform(get("/p/$id")).andExpect(status().isOk).andReturn().response.contentAsString
        assertContains(shell, "&lt;script&gt;parent.pwned=true&lt;/script&gt;")
        assertFalse(shell.contains("<script>parent.pwned=true</script>"))
        val download = mvc.perform(get("/p/$id/download")).andExpect(status().isOk).andReturn().response
        val disposition = assertNotNull(download.getHeader("Content-Disposition"))
        assertFalse(disposition.contains('\r'))
        assertFalse(disposition.contains('\n'))
        assertFalse(disposition.contains("../"))
        assertContains(disposition, ".html")
        assertEquals("<p>original</p>", download.getContentAsString(Charsets.UTF_8))
    }

    @Test
    fun `anonymous viewers can post document comments using the viewer CSRF token and read newest first`() {
        // Arrange
        val id = publish("<h1>Comments</h1>")
        setVisibility(id, "public")
        // Act & Assert
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
                mvc.perform(get("/p/$id/comments")).andExpect(status().isOk).andReturn().response.contentAsString
            )
        assertTrue(empty.isArray)
        assertEquals(0, empty.size())

        val body = "<script>alert('plain text')</script>\nSecond line"
        val first =
            json.readTree(
                mvc.perform(
                        post("/p/$id/comments")
                            .cookie(session)
                            .header("X-CSRF-TOKEN", token)
                            .contentType("application/json")
                            .content(json.writeValueAsString(mapOf("body" to body, "version" to 1)))
                    )
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
            )
        assertEquals("Anonymous", first.path("author").asString())
        assertEquals(body, first.path("body").asString())
        val stored =
            jdbc.queryForMap(
                """
                select c.body, c.author_user_id, v.version, t.xpath from comments c
                join comment_threads t on t.id = c.thread_id
                join html_page_versions v on v.id = c.created_in_version_id where c.id = ?
                """
                    .trimIndent(),
                first.path("id").asLong(),
            )
        assertEquals(body, stored["body"])
        assertNull(stored["author_user_id"])
        assertNull(stored["xpath"])
        assertEquals(1, stored["version"])

        mvc.perform(
                post("/p/$id/comments")
                    .with(member())
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"body":"Newer comment","version":1}""")
            )
            .andExpect(status().isCreated)
        val listed =
            json.readTree(
                mvc.perform(get("/p/$id/comments")).andExpect(status().isOk).andReturn().response.contentAsString
            )
        assertTrue(listed.isArray)
        assertEquals(2, listed.size())
        assertEquals("Newer comment", listed[0].path("body").asString())
        assertEquals("Alex Morgan", listed[0].path("author").asString())
        assertEquals(first.path("id").asLong(), listed[1].path("id").asLong())
        assertEquals(
            2,
            jdbc.queryForObject(
                "select count(*) from comment_threads where artifact_id = ?",
                Int::class.java,
                id,
            ),
        )
    }

    @Test
    fun `Markdown discussions persist rendered element anchors without modifying source`() {
        // Arrange
        val source = "# Review\n\nPlease review **this paragraph**."
        val id = publish(source, "review.md", "text/markdown")
        val xpath = "/html[1]/body[1]/main[1]/p[1]/strong[1]"

        // Act
        val created =
            json.readTree(
                mvc.perform(
                        post("/p/$id/comments")
                            .with(member())
                            .with(csrf())
                            .contentType("application/json")
                            .content(
                                json.writeValueAsString(
                                    mapOf(
                                        "body" to "Feedback on bold text",
                                        "version" to 1,
                                        "xpath" to xpath,
                                        "relativeX" to 0.25,
                                        "relativeY" to 0.75,
                                    )
                                )
                            )
                    )
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
            )

        // Assert
        val listed =
            json
                .readTree(
                    mvc.perform(get("/p/$id/comments").with(member()))
                        .andExpect(status().isOk)
                        .andReturn()
                        .response
                        .contentAsString
                )
                .single()
        assertEquals(created.path("id").asLong(), listed.path("id").asLong())
        assertEquals(xpath, listed.path("xpath").asString())
        assertEquals(0.25, listed.path("relativeX").asDouble())
        assertEquals(0.75, listed.path("relativeY").asDouble())
        assertEquals(
            source,
            mvc.perform(get("/p/$id/download").with(member()))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString,
        )
        assertContains(
            mvc.perform(get("/p/$id/content").with(member()))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString,
            "<p>Please review <strong>this paragraph</strong>.</p>",
        )
    }

    @Test
    fun `attached threads retain XPath and relative coordinates across updates without changing artifact HTML`() {
        // Arrange
        val html = "<html><body><section><h1>Attach here</h1></section></body></html>"
        val id = publish(html)
        setVisibility(id, "public")
        val xpath = "/html[1]/body[1]/section[1]/h1[1]"
        // Act & Assert
        val created =
            json.readTree(
                mvc.perform(
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
                                    )
                                )
                            )
                    )
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
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
            jdbc.queryForObject(
                "select xpath from comment_threads where id = ?",
                String::class.java,
                created.path("threadId").asLong(),
            ),
        )
        assertEquals(
            html,
            mvc.perform(get("/p/$id/content")).andExpect(status().isOk).andReturn().response.contentAsString,
        )
        assertEquals(
            html,
            mvc.perform(get("/p/$id/download")).andExpect(status().isOk).andReturn().response.contentAsString,
        )
        mvc.perform(
                post("/p/$id/threads/${created.path("threadId").asLong()}/comments")
                    .with(csrf())
                    .contentType("application/json")
                    .content("""{"body":"Second message","version":1}""")
            )
            .andExpect(status().isCreated)
        mvc.perform(
                post("/api/pages/$id/replace")
                    .with(httpBasic("htmlshare", "htmlshare"))
                    .contentType("text/html; charset=utf-8")
                    .content("<html><body><p>Element removed</p></body></html>")
            )
            .andExpect(status().isOk)
        val listed =
            json.readTree(
                mvc.perform(get("/p/$id/comments")).andExpect(status().isOk).andReturn().response.contentAsString
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
            jdbc.queryForObject(
                "select xpath from comment_threads where id = ?",
                String::class.java,
                created.path("threadId").asLong(),
            ),
        )
        val otherId = publish("<h1>Other artifact</h1>")
        val other =
            json.readTree(
                mvc.perform(get("/p/$otherId/comments").with(member()))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString
            )
        assertTrue(other.isArray)
        assertEquals(0, other.size())
        mvc.perform(
                post("/p/$id/comments")
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        """{"body":"Invalid anchor","version":2,"xpath":"/html[","relativeX":0.5,"relativeY":0.5}"""
                    )
            )
            .andExpect(status().isBadRequest)
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from comment_threads where artifact_id = ?",
                Int::class.java,
                id,
            ),
        )
    }

    @Test
    fun `invalid or incomplete relative coordinates do not create threads`() {
        // Arrange
        val id = publish("<h1>Coordinates</h1>")
        setVisibility(id, "public")
        // Act & Assert
        for (anchor in
            listOf(
                mapOf("xpath" to "/html/body/h1", "relativeX" to -0.1, "relativeY" to 0.5),
                mapOf("xpath" to "/html/body/h1", "relativeX" to 0.5, "relativeY" to 1.1),
                mapOf("xpath" to "/html/body/h1", "relativeX" to 0.5),
                mapOf("xpath" to "/html/body/h1"),
                mapOf("relativeX" to 0.5, "relativeY" to 0.5),
            )) {
            val request = mapOf("body" to "Invalid coordinates", "version" to 1) + anchor
            mvc.perform(
                    post("/p/$id/comments")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(request))
                )
                .andExpect(status().isBadRequest)
        }
        assertEquals(
            0,
            jdbc.queryForObject(
                "select count(*) from comment_threads where artifact_id = ?",
                Int::class.java,
                id,
            ),
        )
    }

    @Test
    fun `concurrent threads get unique per-artifact numbers that are not reused after deletion`() {
        // Arrange
        val id = publish("<h1>Concurrent discussions</h1>")
        val otherId = publish("<h1>Separate numbering</h1>")
        setVisibility(id, "public")
        setVisibility(otherId, "public")

        fun add(
            artifactId: String,
            body: String,
        ) =
            json.readTree(
                mvc.perform(
                        post("/p/$artifactId/comments")
                            .with(csrf())
                            .contentType("application/json")
                            .content(json.writeValueAsString(mapOf("body" to body, "version" to 1)))
                    )
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
            )

        val start = CountDownLatch(1)
        // Act & Assert
        val created =
            Executors.newFixedThreadPool(8).use { executor ->
                val pending =
                    (1..8).map { index ->
                        executor.submit<tools.jackson.databind.JsonNode> {
                            check(start.await(10, TimeUnit.SECONDS))
                            add(id, "Discussion $index")
                        }
                    }
                start.countDown()
                pending.map { it.get(30, TimeUnit.SECONDS) }
            }
        assertEquals((1L..8L).toList(), created.map { it.path("threadNumber").asLong() }.sorted())
        assertEquals(
            (1L..8L).toList(),
            jdbc.queryForList(
                "select number from comment_threads where artifact_id = ? order by number",
                Long::class.java,
                id,
            ),
        )
        assertEquals(
            8,
            jdbc.queryForObject(
                "select count(*) from comments c join comment_threads t on t.id = c.thread_id where t.artifact_id = ?",
                Int::class.java,
                id,
            ),
        )
        assertEquals(1, add(otherId, "Independent discussion").path("threadNumber").asInt())

        jdbc.update("delete from comment_threads where artifact_id = ? and number = 8", id)
        val next = add(id, "Do not reuse the deleted number")
        assertEquals(9, next.path("threadNumber").asInt())
        assertEquals(
            9,
            jdbc.queryForObject(
                "select number from comment_threads where id = ?",
                Int::class.java,
                next.path("threadId").asLong(),
            ),
        )
    }

    @Test
    fun `replies preserve thread ownership and record their author and viewed version`() {
        // Arrange
        val id = publish(ArtifactVisibility.PUBLIC)
        val first = createComment(id, "First message", anonymous())
        val threadId = first.path("threadId").asLong()
        requireNotNull(pages.update(id, "<p>Updated</p>", null))
        val identity = member()
        val before = snapshot(id)
        assertEquals(2, before.page["current_version"])
        assertEquals(listOf(1, 2), before.versions.map { it["version"] })
        assertNull(before.threads.single()["created_by_user_id"])

        // Act
        val response =
            mvc.perform(
                    post("/p/$id/threads/$threadId/comments")
                        .with(identity)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""{"body":"Reply on the newer version","version":2}""")
                )
                .andReturn()
                .response

        // Assert
        assertEquals(201, response.status)
        val reply = json.readTree(response.contentAsString)
        assertEquals(threadId, reply.path("threadId").asLong())
        assertEquals(1, reply.path("threadNumber").asInt())
        assertEquals("Alex Morgan", reply.path("author").asString())
        assertEquals("Reply on the newer version", reply.path("body").asString())
        val stored =
            jdbc.queryForMap(
                """
                select c.body, c.thread_id, u.subject, cv.version as comment_version, tv.version as thread_version
                from comments c join users u on u.id = c.author_user_id
                join comment_threads t on t.id = c.thread_id
                join html_page_versions cv on cv.id = c.created_in_version_id
                join html_page_versions tv on tv.id = t.created_in_version_id where c.id = ?
                """
                    .trimIndent(),
                reply.path("id").asLong(),
            )
        assertEquals("Reply on the newer version", stored["body"])
        assertEquals(threadId, stored["thread_id"])
        assertEquals("different-team-member", stored["subject"])
        assertEquals(2, stored["comment_version"])
        assertEquals(1, stored["thread_version"])
        val after = snapshot(id)
        assertEquals(2, after.comments.size)
        assertEquals(before.comments.single(), after.comments.first())
        assertEquals(before.copy(comments = after.comments), after)
    }

    @Test
    fun `your artifacts lists only creations by the signed-in user newest first excluding the current artifact`() {
        // Arrange
        val subject = UUID.randomUUID().toString()
        val identity = AuthenticatedUser(subject, "alex@example.com", "Alex Morgan")
        val author = users.provision(identity)
        val older = pages.publish("Older artifact", "<p>Older</p>", PageContentFormat.HTML, author)
        val newer = pages.publish("Newer artifact", "# Newer", PageContentFormat.MARKDOWN, author)
        val current = pages.publish("Current artifact", "<p>Current</p>", PageContentFormat.HTML, author)
        val other =
            pages.publish(
                "Another user's artifact",
                "<p>Other</p>",
                PageContentFormat.HTML,
                users.provision(identity.copy(subject = UUID.randomUUID().toString())),
            )
        pages.update(older.id, "<p>Updated after the newer artifact</p>", author)
        pages.update(other.id, "<p>Edited but not created by this user</p>", author)

        // Act
        val response =
            mvc.perform(get("/account/artifacts").param("excludeId", current.id).with(member(subject = subject)))
                .andExpect(status().isOk)
                .andReturn()
                .response
        // Assert
        val listed = json.readTree(response.contentAsString)
        assertTrue(listed.isArray)
        assertEquals(2, listed.size())
        assertEquals(
            listOf(newer.id, older.id),
            (0 until listed.size()).map { listed[it].path("id").asString() },
        )
        assertEquals("markdown", listed[0].path("format").asString())
        assertEquals("Newer artifact", listed[0].path("title").asString())
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val shell =
            mvc.perform(get("/p/${current.id}").with(member(subject = subject)))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        assertContains(shell, "data-testid=\"${ArtifactViewerIds.ARTIFACTS}\"")
        assertContains(shell, "Your artifacts")
    }

    @Test
    fun `your artifacts requires a team login and returns an empty array for users without publications`() {
        // Act & Assert
        mvc.perform(get("/account/artifacts")).andExpect(status().isUnauthorized)
        mvc.perform(get("/account/artifacts").with(member("outsider@outside.test"))).andExpect(status().isForbidden)
        val response =
            mvc.perform(get("/account/artifacts").with(member(subject = UUID.randomUUID().toString())))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val listed = json.readTree(response.contentAsString)
        assertTrue(listed.isArray)
        assertEquals(0, listed.size())
    }

    @Test
    fun `wrapper protects session data and preserves artifact source`() {
        // Arrange
        val raw =
            "<html><body><h1>Original source marker</h1>" +
                "<script>window.artifactScript = true;</script></body></html>"
        val id = publish(raw, "<script>Title</script>")
        setVisibility(id, "public")

        // Act & Assert
        val shell = mvc.perform(get("/p/$id")).andExpect(status().isOk).andReturn().response.contentAsString
        assertFalse(shell.contains("Original source marker"))

        val response = mvc.perform(get("/p/$id/wrapper")).andExpect(status().isOk).andReturn().response
        val wrapper = response.contentAsString
        assertContains(wrapper, "&lt;script&gt;Title&lt;/script&gt;")
        assertFalse(wrapper.contains("Original source marker"))
        assertFalse(wrapper.contains("data-csrf"))
        assertFalse(wrapper.contains("comment-composer"))
        assertEquals("no-store", response.getHeader("Cache-Control"))
        for (representation in listOf("content", "download")) {
            assertEquals(
                raw,
                mvc.perform(get("/p/$id/$representation"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString,
            )
        }
        setVisibility(id, "internal")
        mvc.perform(get("/p/$id/wrapper").with(member())).andExpect(status().isOk)
        mvc.perform(get("/p/$id/wrapper").with(member("outsider@outside.test"))).andExpect(status().isForbidden)
        mvc.perform(get("/p/$id/wrapper")).andExpect(status().isFound)
    }

    @Test
    fun `comments record the viewed version rather than the latest artifact version`() {
        // Arrange
        val id = publish("<h1>Original</h1>")
        mvc.perform(
                post("/api/pages/$id/replace")
                    .with(httpBasic("htmlshare", "htmlshare"))
                    .contentType("text/html; charset=utf-8")
                    .content("<h1>Updated</h1>")
            )
            .andExpect(status().isOk)
        // Act
        val response =
            json.readTree(
                mvc.perform(
                        post("/p/$id/comments")
                            .with(member())
                            .with(csrf())
                            .contentType("application/json")
                            .content("""{"body":"About the original","version":1}""")
                    )
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
            )
        // Assert
        val versions =
            jdbc.queryForMap(
                """
                select t.created_in_version_id as thread_version,
                    c.created_in_version_id as comment_version, v.id as expected_version
                from comments c join comment_threads t on t.id = c.thread_id
                join html_page_versions v on v.page_id = t.artifact_id and v.version = 1 where c.id = ?
                """
                    .trimIndent(),
                response.path("id").asLong(),
            )
        assertEquals(versions["expected_version"], versions["thread_version"])
        assertEquals(versions["expected_version"], versions["comment_version"])
        assertEquals(2, committedVersion(id))
    }

    private fun assertInternalRequiresLogin(id: String) {
        for (path in listOf("", "/wrapper", "/content", "/download")) {
            val response = mvc.perform(get("/p/$id$path")).andExpect(status().isFound).andReturn().response
            val location = URI.create(assertNotNull(response.redirectedUrl))
            assertEquals("/login", location.path)
            assertEquals(
                "/p/$id$path",
                URLDecoder.decode(location.rawQuery.substringAfter("redirectUrl="), Charsets.UTF_8),
            )
            assertTrue(response.contentAsString.isEmpty())
        }
        val state = mvc.perform(get("/p/$id/status")).andExpect(status().isUnauthorized).andReturn().response
        assertNull(state.redirectedUrl)
    }

    @Test
    fun `resolution rejects anonymous outsiders missing CSRF mismatched artifacts and deleted threads`() {
        // Arrange
        val id = publish("<p>Resolution access</p>")
        val otherId = publish("<p>Other</p>")
        setVisibility(id, "public")
        val first =
            json.readTree(
                mvc.perform(
                        post("/p/$id/comments")
                            .with(member())
                            .with(csrf())
                            .contentType("application/json")
                            .content("""{"body":"Review","version":1}""")
                    )
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
            )
        val threadId = first.path("threadId").asLong()
        val before = jdbc.queryForMap("select * from comment_threads where id = ?", threadId)
        // Act & Assert
        for (resolved in listOf(true, false)) {
            fun request(
                artifactId: String = id,
                target: Long = threadId,
            ) =
                patch("/p/$artifactId/threads/$target/resolution")
                    .contentType("application/json")
                    .content(json.writeValueAsString(mapOf("resolved" to resolved)))
            mvc.perform(request().with(csrf())).andExpect(status().isForbidden)
            mvc.perform(request().with(member("outsider@elsewhere.test")).with(csrf())).andExpect(status().isForbidden)
            mvc.perform(request().with(member())).andExpect(status().isForbidden)
            mvc.perform(request(otherId).with(member()).with(csrf())).andExpect(status().isNotFound)
            mvc.perform(request(target = Long.MAX_VALUE).with(member()).with(csrf())).andExpect(status().isNotFound)
        }
        assertEquals(
            before,
            jdbc.queryForMap("select * from comment_threads where id = ?", threadId),
        )
        mvc.perform(delete("/p/$id/comments/${first.path("id").asLong()}").with(member()).with(csrf()))
            .andExpect(status().isNoContent)
        for (resolved in listOf(true, false)) {
            mvc.perform(
                    patch("/p/$id/threads/$threadId/resolution")
                        .with(member())
                        .with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(mapOf("resolved" to resolved)))
                )
                .andExpect(status().isNotFound)
        }
        assertNull(
            jdbc.queryForObject(
                "select resolved_at from comment_threads where id = ?",
                Timestamp::class.java,
                threadId,
            )
        )
    }

    private fun publish(
        contents: String,
        title: String = "test.html",
        mime: String = "text/html",
    ): String {
        val response =
            mvc.perform(
                    post("/api/pages")
                        .with(httpBasic("htmlshare", "htmlshare"))
                        .contentType("application/json")
                        .content(
                            json.writeValueAsString(
                                mapOf(
                                    "title" to title,
                                    "content" to contents,
                                    "mimeType" to mime,
                                )
                            )
                        )
                )
                .andExpect(status().isOk)
                .andReturn()
                .response
        val url = json.readTree(response.contentAsString).path("url").asString()
        assertTrue(url.contains("/p/"))
        return url.substringAfterLast('/')
    }

    private fun setVisibility(
        id: String,
        visibility: String,
    ) =
        json.readTree(
            mvc.perform(
                    post("/api/pages/$id/visibility")
                        .with(httpBasic("htmlshare", "htmlshare"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(mapOf("visibility" to visibility)))
                )
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        )

    private fun deadline(id: String): Instant? =
        jdbc
            .queryForObject(
                "select public_until from html_pages where id = ?",
                Timestamp::class.java,
                id,
            )
            ?.toInstant()

    private fun committedVersion(id: String) =
        jdbc.queryForObject(
            "select current_version from html_pages where id = ?",
            Int::class.java,
            id,
        )

    private fun versionCount(id: String) =
        jdbc.queryForObject(
            "select count(*) from html_page_versions where page_id = ?",
            Int::class.java,
            id,
        )

    private fun committedContents(id: String) =
        jdbc.queryForObject(
            """
            select v.html from html_page_versions v join html_pages p
            on p.id = v.page_id and p.current_version = v.version where p.id = ?
            """
                .trimIndent(),
            String::class.java,
            id,
        )
}
