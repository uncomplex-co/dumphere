package dev.uncomplex.dumphere.ports

import java.time.Instant

data class CommentRecord(
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
)

data class CommentThreadRecord(
    val threadId: Long,
    val threadNumber: Long,
    val firstCommentId: Long,
    val firstComment: String,
    val author: String,
    val createdAt: Instant,
    val lastActivityAt: Instant,
    val replyCount: Long,
    val xpath: String?,
    val relativeX: Double?,
    val relativeY: Double?,
    val resolvedAt: Instant?,
)
