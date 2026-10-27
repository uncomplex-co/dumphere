package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.CommentThreadEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface CommentThreadRepository : CrudRepository<CommentThreadEntity, Long> {
    @Query("select * from comment_threads where id = :id for update")
    fun findByIdForUpdate(id: Long): CommentThreadEntity?

    fun findAllByArtifactIdOrderByCreatedAtAscIdAsc(artifactId: String): List<CommentThreadEntity>
}
