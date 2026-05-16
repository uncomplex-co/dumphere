package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.HtmlPageVersionEntity
import org.springframework.data.repository.CrudRepository

interface HtmlPageVersionRepository : CrudRepository<HtmlPageVersionEntity, Long> {
    fun findByPageIdAndVersion(
        pageId: String,
        version: Int,
    ): HtmlPageVersionEntity?
}
