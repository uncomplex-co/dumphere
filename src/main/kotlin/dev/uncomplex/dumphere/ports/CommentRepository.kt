package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.ArtifactComment
import dev.uncomplex.dumphere.application.CommentEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface CommentRepository : CrudRepository<CommentEntity, Long> {
    @Query(
        """
        select c.id, c.thread_id, c.body, c.created_at, t.xpath, t.relative_x, t.relative_y,
               case when c.author_user_id is null then 'Anonymous'
                    else coalesce(u.display_name, 'Team member') end as author
        from comments c
        join comment_threads t on t.id = c.thread_id
        left join users u on u.id = c.author_user_id
        where t.artifact_id = :artifactId
        order by max(c.created_at) over (partition by c.thread_id) desc,
                 t.id desc, c.created_at asc, c.id asc
        """,
    )
    fun findArtifactComments(artifactId: String): List<ArtifactComment>

    fun findAllByThreadIdOrderByCreatedAtAscIdAsc(threadId: Long): List<CommentEntity>
}
