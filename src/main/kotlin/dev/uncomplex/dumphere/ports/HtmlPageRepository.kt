package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.HtmlPageEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface HtmlPageRepository : CrudRepository<HtmlPageEntity, String> {
    @Query("select * from html_pages where id = :id for update")
    fun findByIdForUpdate(id: String): HtmlPageEntity?
}
