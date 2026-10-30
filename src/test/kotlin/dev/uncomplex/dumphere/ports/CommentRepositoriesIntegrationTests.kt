package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.CommentEntity
import dev.uncomplex.dumphere.application.CommentThreadEntity
import dev.uncomplex.dumphere.application.HtmlPageVersionEntity
import dev.uncomplex.dumphere.application.UserEntity
import dev.uncomplex.dumphere.testing.PostgresTestDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest(
    properties = [
        "spring.security.oauth2.client.registration.google.client-id=test-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-secret",
    ],
)
class CommentRepositoriesIntegrationTests {
    @Autowired private lateinit var threads: CommentThreadRepository

    @Autowired private lateinit var comments: CommentRepository

    @Autowired private lateinit var versions: HtmlPageVersionRepository

    @Autowired private lateinit var users: UserRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var transactions: TransactionTemplate

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = PostgresTestDatabase.configure(registry)
    }

    @Test
    fun `anonymous thread and first comment retain their version and XPath after artifact updates`() {
        val artifactId = UUID.randomUUID().toString()
        val now = Instant.parse("2026-10-24T12:00:00Z")
        val version = createArtifact(artifactId, now)
        val versionId = assertNotNull(version.id)
        val thread =
            assertNotNull(
                transactions.execute {
                    val thread =
                        threads.save(
                            CommentThreadEntity(
                                artifactId = artifactId,
                                number = 1,
                                createdInVersionId = versionId,
                                createdAt = now,
                                xpath = "/html/body/div[2]",
                                relativeX = 0.25,
                                relativeY = 0.75,
                            ),
                        )
                    comments.save(
                        CommentEntity(
                            threadId = assertNotNull(thread.id),
                            createdInVersionId = versionId,
                            body = "<b>Plain text</b>\nSecond line",
                            createdAt = now,
                        ),
                    )
                    thread
                },
            )

        versions.save(HtmlPageVersionEntity(pageId = artifactId, version = 2, html = "<main>Replacement</main>",
            bytes = 24, createdAt = now))
        jdbc.update("update html_pages set current_version = 2 where id = ?", artifactId)

        val threadId = assertNotNull(thread.id)
        val stored = threads.findById(threadId).orElseThrow()
        assertEquals(version.id, stored.createdInVersionId)
        assertEquals("/html/body/div[2]", stored.xpath)
        assertEquals(0.25, stored.relativeX)
        assertEquals(0.75, stored.relativeY)
        assertNull(stored.createdByUserId)
        val message = comments.findAllByThreadIdOrderByCreatedAtAscIdAsc(threadId).single()
        assertEquals(version.id, message.createdInVersionId)
        assertNull(message.authorUserId)
        assertEquals("<b>Plain text</b>\nSecond line", message.body)
    }

    @Test
    fun `document-level thread supports authenticated replies from different versions in chronological order`() {
        val artifactId = UUID.randomUUID().toString()
        val now = Instant.parse("2026-10-24T12:00:00Z")
        val firstVersion = createArtifact(artifactId, now)
        val firstVersionId = assertNotNull(firstVersion.id)
        val secondVersion = versions.save(HtmlPageVersionEntity(pageId = artifactId, version = 2, html = firstVersion.html,
            bytes = firstVersion.bytes, createdAt = now))
        val author = users.save(UserEntity(subject = UUID.randomUUID().toString(), createdAt = now, updatedAt = now))
        val thread =
            assertNotNull(
                transactions.execute {
                    val thread =
                        threads.save(
                            CommentThreadEntity(
                                artifactId = artifactId,
                                number = 1,
                                createdInVersionId = firstVersionId,
                                createdByUserId = author.id,
                                createdAt = now,
                            ),
                        )
                    comments.save(
                        CommentEntity(
                            threadId = assertNotNull(thread.id),
                            createdInVersionId = firstVersionId,
                            authorUserId = author.id,
                            body = "First comment",
                            createdAt = now,
                        ),
                    )
                    thread
                },
            )

        val threadId = assertNotNull(thread.id)
        val reply =
            comments.save(
                CommentEntity(
                    threadId = threadId,
                    createdInVersionId = assertNotNull(secondVersion.id),
                    authorUserId = author.id,
                    body = "Reply on the newer version",
                    createdAt = now.plusSeconds(1),
                ),
            )

        assertEquals(listOf(threadId), threads.findAllByArtifactIdOrderByCreatedAtAscIdAsc(artifactId).map { it.id })
        assertNull(threads.findById(threadId).orElseThrow().xpath)
        val messages = comments.findAllByThreadIdOrderByCreatedAtAscIdAsc(threadId)
        assertEquals(listOf(firstVersion.id, secondVersion.id), messages.map { it.createdInVersionId })
        assertEquals(listOf(author.id, author.id), messages.map { it.authorUserId })
        assertEquals(reply.id, messages.last().id)
        assertEquals("Reply on the newer version", comments.findById(assertNotNull(reply.id)).orElseThrow().body)
    }

    private fun createArtifact(
        id: String,
        now: Instant,
    ): HtmlPageVersionEntity {
        jdbc.update(
            "insert into html_pages (id, title, url, created_at, current_version, current_bytes) values (?, ?, ?, ?, 1, 0)",
            id,
            "Test artifact",
            "http://localhost:7331/p/$id",
            java.sql.Timestamp.from(now),
        )
        return versions.save(HtmlPageVersionEntity(pageId = id, version = 1, html = "<div>Original</div>", bytes = 0, createdAt = now))
    }
}
