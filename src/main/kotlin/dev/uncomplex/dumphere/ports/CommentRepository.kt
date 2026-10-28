package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.ArtifactComment
import dev.uncomplex.dumphere.application.ArtifactCommentThread
import dev.uncomplex.dumphere.application.CommentEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.jdbc.repository.query.Modifying
import java.time.Instant
import org.springframework.data.repository.CrudRepository

interface CommentRepository : CrudRepository<CommentEntity, Long> {
    @Query(
        """
        select c.id, c.thread_id, t.number as thread_number, c.body, c.created_at, c.edited_at, t.resolved_at,
               coalesce(u.subject = :subject, false) as can_edit, t.xpath, t.relative_x, t.relative_y,
               case when c.author_user_id is null then 'Anonymous'
                    else coalesce(u.display_name, 'Team member') end as author
        from comments c
        join comment_threads t on t.id = c.thread_id
        left join users u on u.id = c.author_user_id
        where t.artifact_id = :artifactId and t.deleted_at is null and c.deleted_at is null
        order by max(c.created_at) over (partition by c.thread_id) desc,
                 t.id desc, c.created_at asc, c.id asc
        """,
    )
    fun findArtifactComments(artifactId: String, subject: String? = null): List<ArtifactComment>

    @Query(
        """
        select c.id, c.thread_id, t.number as thread_number, c.body, c.created_at, c.edited_at, t.resolved_at,
               coalesce(u.subject = :subject, false) as can_edit, t.xpath, t.relative_x, t.relative_y,
               case when c.author_user_id is null then 'Anonymous'
                    else coalesce(u.display_name, 'Team member') end as author
        from comments c
        join comment_threads t on t.id = c.thread_id
        left join users u on u.id = c.author_user_id
        where t.artifact_id = :artifactId and t.id = :threadId and t.deleted_at is null and c.deleted_at is null
        order by c.created_at asc, c.id asc
        """,
    )
    fun findThreadComments(artifactId: String, threadId: Long, subject: String? = null): List<ArtifactComment>

    @Query(
        """
        select * from (
            select distinct on (t.id) t.id as thread_id, t.number as thread_number, c.body as first_comment,
                   case when c.author_user_id is null then 'Anonymous'
                        else coalesce(u.display_name, 'Team member') end as author,
                   c.created_at, t.xpath, t.relative_x, t.relative_y, t.resolved_at,
                   count(*) over (partition by t.id) - 1 as reply_count,
                   max(c.created_at) over (partition by t.id) as last_activity_at
            from comment_threads t
            join comments c on c.thread_id = t.id
            left join users u on u.id = c.author_user_id
            where t.artifact_id = :artifactId and t.deleted_at is null and c.deleted_at is null
            order by t.id, c.created_at asc, c.id asc
        ) summaries
        order by last_activity_at desc, thread_id desc
        """,
    )
    fun findArtifactThreads(artifactId: String): List<ArtifactCommentThread>

    fun findAllByThreadIdOrderByCreatedAtAscIdAsc(threadId: Long): List<CommentEntity>

    @Query("select id from comments where thread_id = :threadId order by created_at, id limit 1")
    fun findFirstCommentId(threadId: Long): Long?

    @Modifying
    @Query("update comments set deleted_at = :deletedAt where thread_id = :threadId and deleted_at is null")
    fun softDeleteThreadComments(threadId: Long, deletedAt: Instant): Int
}
