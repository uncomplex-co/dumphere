package dev.uncomplex.dumphere.application

import dev.uncomplex.dumphere.ports.CommentRepository
import dev.uncomplex.dumphere.ports.CommentThreadRepository
import dev.uncomplex.dumphere.ports.HtmlPageRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class ArtifactComment(
    val id: Long,
    val threadId: Long,
    val threadNumber: Long,
    val body: String,
    val author: String,
    val createdAt: Instant,
    val xpath: String? = null,
    val relativeX: Double? = null,
    val relativeY: Double? = null,
)

data class ArtifactCommentThread(
    val threadId: Long,
    val threadNumber: Long,
    val firstComment: String,
    val author: String,
    val createdAt: Instant,
    val lastActivityAt: Instant,
    val replyCount: Long,
    val xpath: String? = null,
    val relativeX: Double? = null,
    val relativeY: Double? = null,
)

@Service
class ArtifactComments(
    private val threads: CommentThreadRepository,
    private val comments: CommentRepository,
    private val users: UserProvisioningService,
    private val pages: HtmlPageRepository,
) {
    fun list(artifactId: String): List<ArtifactComment> = comments.findArtifactComments(artifactId)

    fun listThreads(artifactId: String): List<ArtifactCommentThread> = comments.findArtifactThreads(artifactId)

    fun listThread(artifactId: String, threadId: Long): List<ArtifactComment> =
        comments.findThreadComments(artifactId, threadId)

    @Transactional
    fun add(
        artifactId: String,
        versionId: Long,
        body: String,
        author: AuthenticatedUser?,
        xpath: String? = null,
        relativeX: Double? = null,
        relativeY: Double? = null,
    ): ArtifactComment {
        val user = author?.let(users::provision)
        val now = Instant.now()
        val thread =
            threads.save(
                CommentThreadEntity(
                    artifactId = artifactId,
                    number = requireNotNull(pages.allocateDiscussionThreadNumber(artifactId)) { "Artifact not found" },
                    createdInVersionId = versionId,
                    createdByUserId = user?.id,
                    createdAt = now,
                    xpath = xpath,
                    relativeX = relativeX,
                    relativeY = relativeY,
                ),
            )
        return saveComment(thread, versionId, body, user, now)
    }

    @Transactional
    fun reply(
        thread: CommentThreadEntity,
        versionId: Long,
        body: String,
        author: AuthenticatedUser?,
    ): ArtifactComment = saveComment(thread, versionId, body, author?.let(users::provision), Instant.now())

    private fun saveComment(
        thread: CommentThreadEntity,
        versionId: Long,
        body: String,
        user: UserEntity?,
        now: Instant,
    ): ArtifactComment {
        val comment =
            comments.save(
                CommentEntity(
                    threadId = requireNotNull(thread.id),
                    createdInVersionId = versionId,
                    authorUserId = user?.id,
                    body = body,
                    createdAt = now,
                ),
            )
        return ArtifactComment(
            id = requireNotNull(comment.id),
            threadId = requireNotNull(thread.id),
            threadNumber = thread.number,
            body = comment.body,
            author = if (user == null) "Anonymous" else user.displayName ?: "Team member",
            createdAt = now,
            xpath = thread.xpath,
            relativeX = thread.relativeX,
            relativeY = thread.relativeY,
        )
    }
}
