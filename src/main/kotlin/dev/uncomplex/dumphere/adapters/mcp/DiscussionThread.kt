package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.application.ArtifactCommentThread
import java.time.Instant

data class DiscussionThread(
    val threadId: Long,
    val threadNumber: Long,
    val firstComment: String,
    val author: String,
    val createdAt: Instant,
    val lastActivityAt: Instant,
    val replyCount: Long,
    val xpath: String?,
    val relativeX: Double?,
    val relativeY: Double?,
    val resolvedAt: Instant?,
    val reactions: List<DiscussionReaction>,
) {
    companion object {
        fun from(thread: ArtifactCommentThread) = DiscussionThread(
            threadId = thread.threadId,
            threadNumber = thread.threadNumber,
            firstComment = thread.firstComment,
            author = thread.author,
            createdAt = thread.createdAt,
            lastActivityAt = thread.lastActivityAt,
            replyCount = thread.replyCount,
            xpath = thread.xpath,
            relativeX = thread.relativeX,
            relativeY = thread.relativeY,
            resolvedAt = thread.resolvedAt,
            reactions = DiscussionReaction.from(thread.reactions),
        )
    }
}
