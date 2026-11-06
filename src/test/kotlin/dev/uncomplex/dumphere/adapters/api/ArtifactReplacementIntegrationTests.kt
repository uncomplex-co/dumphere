package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.PageContentFormat
import dev.uncomplex.dumphere.ports.PublishedArtifactsStore
import dev.uncomplex.dumphere.testing.PostgresTestDatabase
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper

/** Real HTTP, security filters and PostgreSQL. No mocked application boundaries or rollback transaction. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        [
            "spring.security.oauth2.client.registration.google.client-id=test-client",
            "spring.security.oauth2.client.registration.google.client-secret=test-secret",
            "htmlshare.api-username=replacement-client",
            "htmlshare.api-password=replacement-password",
        ],
)
class ArtifactReplacementIntegrationTests {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var pages: PublishedArtifactsStore

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var json: ObjectMapper

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = PostgresTestDatabase.configure(registry)
    }

    @ParameterizedTest(name = "replace {0} with {1} returns metadata and preserves the stored format")
    @CsvSource(
        "HTML, text/html",
        "MARKDOWN, text/markdown",
        "HTML, text/markdown",
        "MARKDOWN, text/html",
        "HTML, text/plain",
        "MARKDOWN, application/octet-stream",
    )
    fun `replacement returns metadata and commits a new version without changing the artifact format`(
        format: PageContentFormat,
        mediaType: String,
    ) {
        val original = pages.publish("Original title", "Original document", format, null)
        val content = "Complete replacement ✓"

        val response = send("POST", "/api/pages/${original.id}/replace", content, "$mediaType; charset=utf-8")

        assertEquals(200, response.statusCode(), response.body())
        val metadata = json.readTree(response.body())
        assertEquals(original.id, metadata.path("id").asString())
        assertEquals(original.url, metadata.path("url").asString())
        assertEquals("Original title", metadata.path("title").asString())
        assertEquals(format.name, metadata.path("contentFormat").asString())
        assertEquals(2, metadata.path("version").asInt())
        assertEquals(24, metadata.path("bytes").asInt())
        assertFalse(metadata.has("content"))
        assertNotNull(
            jdbc.queryForObject(
                "select updated_by_user_id from html_pages where id = ?",
                Long::class.java,
                original.id,
            )
        )
        assertEquals(
            2,
            jdbc.queryForObject(
                "select current_version from html_pages where id = ?",
                Int::class.java,
                original.id,
            ),
        )
        assertEquals(
            format.name,
            jdbc.queryForObject(
                "select content_format from html_pages where id = ?",
                String::class.java,
                original.id,
            ),
        )
        assertEquals(
            listOf("Original document", "Complete replacement ✓"),
            jdbc.queryForList(
                "select html from html_page_versions where page_id = ? order by version",
                String::class.java,
                original.id,
            ),
        )
    }

    @Test
    fun `replacement of a missing artifact returns not found without creating it`() {
        val id = "missing-replacement-artifact"

        val response = send("POST", "/api/pages/$id/replace", "Replacement")

        assertEquals(404, response.statusCode(), response.body())
        assertEquals(
            0,
            jdbc.queryForObject(
                "select count(*) from html_pages where id = ?",
                Int::class.java,
                id,
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "select count(*) from html_page_versions where page_id = ?",
                Int::class.java,
                id,
            ),
        )
    }

    @Test
    fun `a JSON envelope is rejected without changing content`() {
        val original = pages.publish("Original", "Original document", PageContentFormat.HTML, null)

        val response =
            send(
                "POST",
                "/api/pages/${original.id}/replace",
                """{"content":"Replacement"}""",
                "application/json",
            )

        assertEquals(415, response.statusCode(), response.body())
        assertEquals(
            1,
            jdbc.queryForObject(
                "select current_version from html_pages where id = ?",
                Int::class.java,
                original.id,
            ),
        )
        assertEquals(
            listOf("Original document"),
            jdbc.queryForList(
                "select html from html_page_versions where page_id = ? order by version",
                String::class.java,
                original.id,
            ),
        )
    }

    @Test
    fun `the old PUT route is unavailable and does not replace content`() {
        val original = pages.publish("Original", "Original document", PageContentFormat.HTML, null)

        val response =
            send(
                "PUT",
                "/api/pages/${original.id}",
                """{"html":"Replacement"}""",
                "application/json",
            )

        assertEquals(405, response.statusCode(), response.body())
        assertEquals(
            1,
            jdbc.queryForObject(
                "select current_version from html_pages where id = ?",
                Int::class.java,
                original.id,
            ),
        )
        assertEquals(
            listOf("Original document"),
            jdbc.queryForList(
                "select html from html_page_versions where page_id = ? order by version",
                String::class.java,
                original.id,
            ),
        )
    }

    private fun send(
        method: String,
        path: String,
        body: String,
        mediaType: String = "text/plain; charset=utf-8",
    ): HttpResponse<String> {
        val credentials = Base64.getEncoder().encodeToString("replacement-client:replacement-password".toByteArray())
        val request =
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
                .header("Authorization", "Basic $credentials")
                .header("Content-Type", mediaType)
                .header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build()
        return HttpClient.newHttpClient().use {
            it.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }
}
