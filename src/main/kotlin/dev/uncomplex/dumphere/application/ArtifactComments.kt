package dev.uncomplex.dumphere.application

import dev.uncomplex.dumphere.ports.CommentRepository
import dev.uncomplex.dumphere.ports.CommentRecord
import dev.uncomplex.dumphere.ports.CommentThreadRecord
import dev.uncomplex.dumphere.ports.CommentThreadRepository
import dev.uncomplex.dumphere.ports.HtmlPageRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

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
    val resolvedAt: Instant? = null,
    val reactions: List<CommentReaction> = emptyList(),
)

data class ArtifactCommentThread(
    val threadId: Long,
    val threadNumber: Long,
    val firstCommentId: Long,
    val firstComment: String,
    val author: String,
    val createdAt: Instant,
    val lastActivityAt: Instant,
    val replyCount: Long,
    val xpath: String? = null,
    val relativeX: Double? = null,
    val relativeY: Double? = null,
    val resolvedAt: Instant? = null,
    val reactions: List<CommentReaction> = emptyList(),
)

data class DiscussionThreadState(val threadId: Long, val threadNumber: Long, val resolvedAt: Instant?)

@Service
class ArtifactComments(
    private val threads: CommentThreadRepository,
    private val comments: CommentRepository,
    private val pages: HtmlPageRepository,
    private val reactions: CommentReactions,
) {
    fun list(artifactId: String, viewer: UserEntity? = null): List<ArtifactComment> =
        withReactions(artifactId, comments.findArtifactComments(artifactId, viewer?.subject), viewer?.subject)

    fun listThreads(artifactId: String): List<ArtifactCommentThread> {
        val current = reactions.list(artifactId, null)
        return comments.findArtifactThreads(artifactId).map { it.toResponse(current[it.firstCommentId].orEmpty()) }
    }

    fun listThread(artifactId: String, threadId: Long): List<ArtifactComment> =
        withReactions(artifactId, comments.findThreadComments(artifactId, threadId), null)

    private fun withReactions(artifactId: String, records: List<CommentRecord>, subject: String?): List<ArtifactComment> {
        val current = reactions.list(artifactId, subject)
        return records.map { it.toResponse(current[it.id].orEmpty()) }
    }

    private fun CommentRecord.toResponse(reactions: List<CommentReaction>) = ArtifactComment(
        id = id,
        threadId = threadId,
        threadNumber = threadNumber,
        body = body,
        author = author,
        createdAt = createdAt,
        xpath = xpath,
        relativeX = relativeX,
        relativeY = relativeY,
        editedAt = editedAt,
        canEdit = canEdit,
        resolvedAt = resolvedAt,
        reactions = reactions,
    )

    private fun CommentThreadRecord.toResponse(reactions: List<CommentReaction>) = ArtifactCommentThread(
        threadId = threadId,
        threadNumber = threadNumber,
        firstCommentId = firstCommentId,
        firstComment = firstComment,
        author = author,
        createdAt = createdAt,
        lastActivityAt = lastActivityAt,
        replyCount = replyCount,
        xpath = xpath,
        relativeX = relativeX,
        relativeY = relativeY,
        resolvedAt = resolvedAt,
        reactions = reactions,
    )

    @Transactional
    fun add(
        artifactId: String,
        versionId: Long,
        body: String,
        author: UserEntity?,
        xpath: String? = null,
        relativeX: Double? = null,
        relativeY: Double? = null,
    ): ArtifactComment {
        val user = author
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
        author: UserEntity?,
    ): ArtifactComment {
        val current = requireNotNull(threads.findByIdForUpdate(requireNotNull(thread.id))?.takeIf { it.deletedAt == null }) {
            "Thread not found"
        }
        return saveComment(current, versionId, body, author, Instant.now())
    }

    @Transactional
    fun edit(artifactId: String, commentId: Long, body: String, author: UserEntity): EditCommentResult {
        val existing = comments.findById(commentId).orElse(null) ?: return EditCommentResult.NotFound
        val thread = threads.findByIdForUpdate(existing.threadId)
            ?.takeIf { it.artifactId == artifactId && it.deletedAt == null } ?: return EditCommentResult.NotFound
        val comment = comments.findById(commentId).orElse(null)?.takeIf { it.deletedAt == null }
            ?: return EditCommentResult.NotFound
        val userId = author.id
        if (comment.authorUserId == null || comment.authorUserId != userId) return EditCommentResult.Forbidden

        comment.body = body
        comment.editedAt = Instant.now()
        comments.save(comment)
        return EditCommentResult.Updated(withReactions(artifactId, comments.findThreadComments(artifactId, requireNotNull(thread.id), author.subject), author.subject)
            .single { it.id == commentId })
    }

    @Transactional
    fun delete(artifactId: String, commentId: Long, author: UserEntity): DeleteCommentResult {
        val existing = comments.findById(commentId).orElse(null) ?: return DeleteCommentResult.NOT_FOUND
        val thread = threads.findByIdForUpdate(existing.threadId)
            ?.takeIf { it.artifactId == artifactId && it.deletedAt == null } ?: return DeleteCommentResult.NOT_FOUND
        val comment = comments.findById(commentId).orElse(null)?.takeIf { it.deletedAt == null }
            ?: return DeleteCommentResult.NOT_FOUND
        val userId = author.id
        if (comment.authorUserId == null || comment.authorUserId != userId) return DeleteCommentResult.FORBIDDEN

        val now = Instant.now()
        if (comments.findFirstCommentId(comment.threadId) == commentId) {
            thread.deletedAt = now
            threads.save(thread)
            comments.softDeleteThreadComments(comment.threadId, now)
        } else {
            comment.deletedAt = now
            comments.save(comment)
        }
        return DeleteCommentResult.DELETED
    }

    @Transactional
    fun setResolved(artifactId: String, threadId: Long, resolved: Boolean, author: UserEntity): DiscussionThreadState? {
        val thread = threads.findByIdForUpdate(threadId)
            ?.takeIf { it.artifactId == artifactId && it.deletedAt == null } ?: return null
        if ((thread.resolvedAt != null) == resolved) {
            return DiscussionThreadState(threadId, thread.number, thread.resolvedAt)
        }
        thread.resolvedAt = if (resolved) Instant.now().truncatedTo(ChronoUnit.MICROS) else null
        thread.resolvedByUserId = if (resolved) author.id else null
        val updated = threads.save(thread)
        return DiscussionThreadState(threadId, updated.number, updated.resolvedAt)
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
            resolvedAt = thread.resolvedAt,
        )
    }
}

sealed interface EditCommentResult {
    data class Updated(val comment: ArtifactComment) : EditCommentResult
    data object NotFound : EditCommentResult
    data object Forbidden : EditCommentResult
}

enum class DeleteCommentResult { DELETED, NOT_FOUND, FORBIDDEN }
