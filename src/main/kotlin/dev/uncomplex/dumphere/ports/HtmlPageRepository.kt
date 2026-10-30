package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.HtmlPageEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.repository.CrudRepository
import java.time.Instant

interface HtmlPageRepository : CrudRepository<HtmlPageEntity, String> {
    @Modifying
    @Query(
        """
        insert into html_pages (
            id, title, url, content_format, created_at, created_by_user_id,
            updated_at, updated_by_user_id, current_version, current_bytes, is_live, public_until
        ) values (
            :#{#page.id}, :#{#page.title}, :#{#page.url}, :#{#page.contentFormat.name()},
            cast(:#{#page.createdAt.toString()} as timestamptz), :#{#page.createdByUserId},
            cast(:#{#page.updatedAt?.toString()} as timestamptz), :#{#page.updatedByUserId},
            :#{#page.currentVersion}, :#{#page.currentBytes}, :#{#page.isLive},
            cast(:#{#page.publicUntil?.toString()} as timestamptz)
        )
        """,
    )
    fun insert(page: HtmlPageEntity): Int

    @Query("select custom_metadata::text from html_pages where id = :id")
    fun readCustomMetadata(id: String): String?

    @Modifying
    @Query(
        """
        update html_pages set custom_metadata = cast(:metadata as jsonb), updated_at = :now, updated_by_user_id = :userId
        where id = :id
        """,
    )
    fun updateCustomMetadata(id: String, metadata: String, now: Instant, userId: Long?): Int

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
