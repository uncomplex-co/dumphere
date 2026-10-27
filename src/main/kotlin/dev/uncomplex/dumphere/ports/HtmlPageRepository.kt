package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.HtmlPageEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface HtmlPageRepository : CrudRepository<HtmlPageEntity, String> {
    @Query(
        """
        update html_pages set last_discussion_thread_number = last_discussion_thread_number + 1
        where id = :id returning last_discussion_thread_number
        """,
    )
    fun allocateDiscussionThreadNumber(id: String): Long?

    @Query("select * from html_pages where id = :id for update")
    fun findByIdForUpdate(id: String): HtmlPageEntity?

    @Query(
        """
        select p.* from html_pages p
        join users u on u.id = p.created_by_user_id
        where u.subject = :subject and p.id <> :excludeId
        order by p.created_at desc, p.id desc
        """,
    )
    fun findOtherArtifactsByCreator(
        subject: String,
        excludeId: String,
    ): List<HtmlPageEntity>
}
