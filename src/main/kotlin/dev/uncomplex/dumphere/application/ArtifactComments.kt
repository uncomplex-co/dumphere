package dev.uncomplex.dumphere.application

import dev.uncomplex.dumphere.ports.CommentRepository
import dev.uncomplex.dumphere.ports.CommentThreadRepository
import dev.uncomplex.dumphere.ports.HtmlPageRepository
import dev.uncomplex.dumphere.ports.UserRepository
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
    val editedAt: Instant? = null,
    val canEdit: Boolean = false,
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
    private val userRecords: UserRepository,
) {
    fun list(artifactId: String, viewer: AuthenticatedUser? = null): List<ArtifactComment> =
        comments.findArtifactComments(artifactId, viewer?.subject)

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
    ): ArtifactComment {
        val current = requireNotNull(threads.findByIdForUpdate(requireNotNull(thread.id))?.takeIf { it.deletedAt == null }) {
            "Thread not found"
        }
        return saveComment(current, versionId, body, author?.let(users::provision), Instant.now())
    }

    @Transactional
    fun edit(artifactId: String, commentId: Long, body: String, author: AuthenticatedUser): EditCommentResult {
        val existing = comments.findById(commentId).orElse(null) ?: return EditCommentResult.NotFound
        val thread = threads.findByIdForUpdate(existing.threadId)
            ?.takeIf { it.artifactId == artifactId && it.deletedAt == null } ?: return EditCommentResult.NotFound
        val comment = comments.findById(commentId).orElse(null)?.takeIf { it.deletedAt == null }
            ?: return EditCommentResult.NotFound
        val userId = userRecords.findBySubject(author.subject)?.id
        if (comment.authorUserId == null || comment.authorUserId != userId) return EditCommentResult.Forbidden

        comments.save(comment.copy(body = body, editedAt = Instant.now()))
        return EditCommentResult.Updated(comments.findThreadComments(artifactId, requireNotNull(thread.id), author.subject)
            .single { it.id == commentId })
    }

    @Transactional
    fun delete(artifactId: String, commentId: Long, author: AuthenticatedUser): DeleteCommentResult {
        val existing = comments.findById(commentId).orElse(null) ?: return DeleteCommentResult.NOT_FOUND
        val thread = threads.findByIdForUpdate(existing.threadId)
            ?.takeIf { it.artifactId == artifactId && it.deletedAt == null } ?: return DeleteCommentResult.NOT_FOUND
        val comment = comments.findById(commentId).orElse(null)?.takeIf { it.deletedAt == null }
            ?: return DeleteCommentResult.NOT_FOUND
        val userId = userRecords.findBySubject(author.subject)?.id
        if (comment.authorUserId == null || comment.authorUserId != userId) return DeleteCommentResult.FORBIDDEN

        val now = Instant.now()
        if (comments.findFirstCommentId(comment.threadId) == commentId) {
            threads.save(thread.copy(deletedAt = now))
            comments.softDeleteThreadComments(comment.threadId, now)
        } else {
            comments.save(comment.copy(deletedAt = now))
        }
        return DeleteCommentResult.DELETED
    }

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
            canEdit = user != null,
        )
    }
}

sealed interface EditCommentResult {
    data class Updated(val comment: ArtifactComment) : EditCommentResult
    data object NotFound : EditCommentResult
    data object Forbidden : EditCommentResult
}

enum class DeleteCommentResult { DELETED, NOT_FOUND, FORBIDDEN }
