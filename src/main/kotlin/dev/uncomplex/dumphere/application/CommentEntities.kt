package dev.uncomplex.dumphere.application

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("comment_threads")
data class CommentThreadEntity(
    @Id val id: Long? = null,
    val artifactId: String,
    val number: Long,
    val createdInVersionId: Long,
    val createdByUserId: Long? = null,
    val createdAt: Instant,
    val xpath: String? = null,
    val relativeX: Double? = null,
    val relativeY: Double? = null,
    val deletedAt: Instant? = null,
)

@Table("comments")
data class CommentEntity(
    @Id val id: Long? = null,
    val threadId: Long,
    val createdInVersionId: Long,
    val authorUserId: Long? = null,
    val body: String,
    val createdAt: Instant,
    val editedAt: Instant? = null,
    val deletedAt: Instant? = null,
)
