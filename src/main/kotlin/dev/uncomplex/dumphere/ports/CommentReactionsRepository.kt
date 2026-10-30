package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.CommentReactionEntity
import dev.uncomplex.dumphere.application.CommentReactionVoter
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface CommentReactionsRepository : CrudRepository<CommentReactionEntity, Long> {
    @Query("""
        select r.comment_id, r.emoji, coalesce(u.display_name, 'Team member') as name,
               coalesce(u.subject = :subject, false) as mine
        from comment_reactions r
        join users u on u.id = r.user_id
        join comments c on c.id = r.comment_id
        join comment_threads t on t.id = c.thread_id
        where t.artifact_id = :artifactId and t.deleted_at is null and c.deleted_at is null
        order by r.created_at, r.id
    """)
    fun findArtifactVoters(artifactId: String, subject: String?): List<CommentReactionVoter>

    @Modifying
    @Query("insert into comment_reactions (comment_id, user_id, emoji) values (:commentId, :userId, :emoji) on conflict do nothing")
    fun add(commentId: Long, userId: Long, emoji: String): Int

    @Modifying
    @Query("delete from comment_reactions where comment_id = :commentId and user_id = :userId and emoji = :emoji")
    fun remove(commentId: Long, userId: Long, emoji: String): Int
}
