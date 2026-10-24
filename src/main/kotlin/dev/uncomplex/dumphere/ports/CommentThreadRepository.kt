package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.CommentThreadEntity
import org.springframework.data.repository.CrudRepository

interface CommentThreadRepository : CrudRepository<CommentThreadEntity, Long> {
    fun findAllByArtifactIdOrderByCreatedAtAscIdAsc(artifactId: String): List<CommentThreadEntity>
}
