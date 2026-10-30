package dev.uncomplex.dumphere.application

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("comment_threads")
class CommentThreadEntity(
    @Id var id: Long? = null,
    var artifactId: String,
    var number: Long,
    var createdInVersionId: Long,
    var createdByUserId: Long? = null,
    var createdAt: Instant,
    var xpath: String? = null,
    var relativeX: Double? = null,
    var relativeY: Double? = null,
    var deletedAt: Instant? = null,
    var resolvedAt: Instant? = null,
    var resolvedByUserId: Long? = null,
)

@Table("comments")
class CommentEntity(
    @Id var id: Long? = null,
    var threadId: Long,
    var createdInVersionId: Long,
    var authorUserId: Long? = null,
    var body: String,
    var createdAt: Instant,
    var editedAt: Instant? = null,
    var deletedAt: Instant? = null,
)
