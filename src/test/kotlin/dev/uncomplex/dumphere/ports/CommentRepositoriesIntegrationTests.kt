package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.CommentEntity
import dev.uncomplex.dumphere.application.CommentThreadEntity
import dev.uncomplex.dumphere.application.HtmlPageVersionEntity
import dev.uncomplex.dumphere.application.UserEntity
import dev.uncomplex.dumphere.testing.PostgresTestDatabase
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(
    properties =
        [
            "spring.security.oauth2.client.registration.google.client-id=test-client",
            "spring.security.oauth2.client.registration.google.client-secret=test-secret",
        ]
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
        // Arrange
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
                            )
                        )
                    comments.save(
                        CommentEntity(
                            threadId = assertNotNull(thread.id),
                            createdInVersionId = versionId,
                            body = "<b>Plain text</b>\nSecond line",
                            createdAt = now,
                        )
                    )
                    thread
                }
            )

        // Act
        versions.save(
            HtmlPageVersionEntity(
                pageId = artifactId,
                version = 2,
                html = "<main>Replacement</main>",
                bytes = 24,
                createdAt = now,
            )
        )
        jdbc.update("update html_pages set current_version = 2 where id = ?", artifactId)

        // Assert
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
    fun `saving an authenticated reply retains its viewed version and the original document-level thread`() {
        // Arrange
        val artifactId = UUID.randomUUID().toString()
        val now = Instant.parse("2026-10-24T12:00:00Z")
        val firstVersion = createArtifact(artifactId, now)
        val firstVersionId = assertNotNull(firstVersion.id)
        val secondVersion =
            versions.save(
                HtmlPageVersionEntity(
                    pageId = artifactId,
                    version = 2,
                    html = firstVersion.html,
                    bytes = firstVersion.bytes,
                    createdAt = now,
                )
            )
        val author = users.save(UserEntity(subject = UUID.randomUUID().toString(), createdAt = now, updatedAt = now))
        val authorId = assertNotNull(author.id)
        val thread = documentThread(firstVersion, now, authorId)
        val threadId = assertNotNull(thread.id)
        val beforeThread = jdbc.queryForMap("select * from comment_threads where id = ?", threadId)
        val firstComment = comments.findAllByThreadIdOrderByCreatedAtAscIdAsc(threadId).single()
        val beforeComment = jdbc.queryForMap("select * from comments where id = ?", firstComment.id)
        assertNull(beforeThread["xpath"])
        assertEquals(firstVersionId, beforeThread["created_in_version_id"])
        assertEquals(authorId, beforeThread["created_by_user_id"])

        // Act
        val reply =
            comments.save(
                CommentEntity(
                    threadId = threadId,
                    createdInVersionId = assertNotNull(secondVersion.id),
                    authorUserId = authorId,
                    body = "Reply on the newer version",
                    createdAt = now.plusSeconds(1),
                )
            )

        // Assert
        val stored = comments.findById(assertNotNull(reply.id)).orElseThrow()
        assertEquals(threadId, stored.threadId)
        assertEquals(secondVersion.id, stored.createdInVersionId)
        assertEquals(authorId, stored.authorUserId)
        assertEquals("Reply on the newer version", stored.body)
        assertEquals(now.plusSeconds(1), stored.createdAt)
        assertEquals(beforeThread, jdbc.queryForMap("select * from comment_threads where id = ?", threadId))
        assertEquals(beforeComment, jdbc.queryForMap("select * from comments where id = ?", firstComment.id))
        assertEquals(
            listOf(firstComment.id, reply.id),
            comments.findAllByThreadIdOrderByCreatedAtAscIdAsc(threadId).map { it.id },
        )
    }

    @Test
    fun `thread comments are returned chronologically when insertion order differs from timestamp order`() {
        // Arrange
        val now = Instant.parse("2026-10-24T12:00:00Z")
        val version = createArtifact(UUID.randomUUID().toString(), now)
        val thread = documentThread(version, now)
        val threadId = assertNotNull(thread.id)
        addComment(threadId, version, "Latest reply", now.plusSeconds(3))
        addComment(threadId, version, "Earliest reply", now.plusSeconds(1))
        addComment(threadId, version, "Middle reply", now.plusSeconds(2))

        // Act
        val result = comments.findAllByThreadIdOrderByCreatedAtAscIdAsc(threadId)

        // Assert
        assertEquals(
            listOf("First comment", "Earliest reply", "Middle reply", "Latest reply"),
            result.map { it.body },
        )
    }

    @Test
    fun `thread comments with equal timestamps are returned in ascending ID order`() {
        // Arrange
        val now = Instant.parse("2026-10-24T12:00:00Z")
        val version = createArtifact(UUID.randomUUID().toString(), now)
        val thread = documentThread(version, now)
        val threadId = assertNotNull(thread.id)
        val firstComment = comments.findAllByThreadIdOrderByCreatedAtAscIdAsc(threadId).single()
        val firstReply = addComment(threadId, version, "First tied reply", now.plusSeconds(1))
        val secondReply = addComment(threadId, version, "Second tied reply", now.plusSeconds(1))
        assertTrue(assertNotNull(firstReply.id) < assertNotNull(secondReply.id))

        // Act
        val result = comments.findAllByThreadIdOrderByCreatedAtAscIdAsc(threadId)

        // Assert
        assertEquals(
            listOf(firstComment.id, firstReply.id, secondReply.id),
            result.map { it.id },
        )
    }

    private fun documentThread(
        version: HtmlPageVersionEntity,
        now: Instant,
        authorId: Long? = null,
    ): CommentThreadEntity =
        assertNotNull(
            transactions.execute {
                val thread =
                    threads.save(
                        CommentThreadEntity(
                            artifactId = version.pageId,
                            number = 1,
                            createdInVersionId = assertNotNull(version.id),
                            createdByUserId = authorId,
                            createdAt = now,
                        )
                    )
                comments.save(
                    CommentEntity(
                        threadId = assertNotNull(thread.id),
                        createdInVersionId = assertNotNull(version.id),
                        authorUserId = authorId,
                        body = "First comment",
                        createdAt = now,
                    )
                )
                thread
            }
        )

    private fun addComment(
        threadId: Long,
        version: HtmlPageVersionEntity,
        body: String,
        createdAt: Instant,
    ): CommentEntity =
        comments.save(
            CommentEntity(
                threadId = threadId,
                createdInVersionId = assertNotNull(version.id),
                body = body,
                createdAt = createdAt,
            )
        )

    private fun createArtifact(
        id: String,
        now: Instant,
    ): HtmlPageVersionEntity {
        jdbc.update(
            """
            insert into html_pages (id, title, url, created_at, current_version, current_bytes)
            values (?, ?, ?, ?, 1, 0)
            """
                .trimIndent(),
            id,
            "Test artifact",
            "http://localhost:7331/p/$id",
            java.sql.Timestamp.from(now),
        )
        return versions.save(
            HtmlPageVersionEntity(
                pageId = id,
                version = 1,
                html = "<div>Original</div>",
                bytes = 0,
                createdAt = now,
            )
        )
    }
}
