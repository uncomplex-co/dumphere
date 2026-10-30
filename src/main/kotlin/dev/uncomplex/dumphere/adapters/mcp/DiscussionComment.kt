package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.application.ArtifactComment
import dev.uncomplex.dumphere.application.CommentReaction
import java.time.Instant

data class DiscussionReactions(val commentId: Long, val reactions: List<DiscussionReaction>)

data class DiscussionReaction(val emoji: String, val author: String) {
    companion object {
        fun from(reactions: List<CommentReaction>): List<DiscussionReaction> =
            reactions.flatMap { reaction -> reaction.users.map { DiscussionReaction(reaction.emoji, it) } }
    }
}

// MCP exposes individual authored reactions; the browser uses grouped counts and toggle state.
data class DiscussionComment(
    val id: Long,
    val threadId: Long,
    val threadNumber: Long,
    val body: String,
    val author: String,
    val createdAt: Instant,
    val xpath: String?,
    val relativeX: Double?,
    val relativeY: Double?,
    val editedAt: Instant?,
    val canEdit: Boolean,
    val resolvedAt: Instant?,
    val reactions: List<DiscussionReaction>,
) {
    companion object {
        fun from(comment: ArtifactComment) = DiscussionComment(
            id = comment.id,
            threadId = comment.threadId,
            threadNumber = comment.threadNumber,
            body = comment.body,
            author = comment.author,
            createdAt = comment.createdAt,
            xpath = comment.xpath,
            relativeX = comment.relativeX,
            relativeY = comment.relativeY,
            editedAt = comment.editedAt,
            canEdit = comment.canEdit,
            resolvedAt = comment.resolvedAt,
            reactions = DiscussionReaction.from(comment.reactions),
        )
    }
}
