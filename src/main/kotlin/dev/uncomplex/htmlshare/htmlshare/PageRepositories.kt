package dev.uncomplex.htmlshare.htmlshare

import org.springframework.data.repository.CrudRepository

interface UserRepository : CrudRepository<UserEntity, Long> {
    fun findBySubject(subject: String): UserEntity?
}

interface HtmlPageRepository : CrudRepository<HtmlPageEntity, String>

interface HtmlPageVersionRepository : CrudRepository<HtmlPageVersionEntity, Long> {
    fun findByPageIdAndVersion(pageId: String, version: Int): HtmlPageVersionEntity?
}
