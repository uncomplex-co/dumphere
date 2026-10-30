package dev.uncomplex.dumphere.application

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("users")
class UserEntity(
    @Id var id: Long? = null,
    var subject: String,
    var email: String? = null,
    var displayName: String? = null,
    var createdAt: Instant,
    var updatedAt: Instant,
)

@Table("html_pages")
class HtmlPageEntity(
    @Id @Column("id") var id: String,
    var title: String,
    var url: String,
    var contentFormat: PageContentFormat,
    var createdAt: Instant,
    var createdByUserId: Long? = null,
    var updatedAt: Instant? = null,
    var updatedByUserId: Long? = null,
    var currentVersion: Int,
    var currentBytes: Long,
    var isLive: Boolean = false,
    var publicUntil: Instant? = null,
)

@Table("html_page_versions")
class HtmlPageVersionEntity(
    @Id var id: Long? = null,
    var pageId: String,
    var version: Int,
    var html: String,
    var bytes: Long,
    var createdAt: Instant,
    var createdByUserId: Long? = null,
)
