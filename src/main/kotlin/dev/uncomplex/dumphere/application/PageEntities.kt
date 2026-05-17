package dev.uncomplex.dumphere.application

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("users")
data class UserEntity(
    @Id val id: Long? = null,
    val subject: String,
    val email: String? = null,
    val displayName: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Table("html_pages")
data class HtmlPageEntity(
    @Id @Column("id") val id: String,
    val title: String,
    val url: String,
    val contentFormat: PageContentFormat,
    val createdAt: Instant,
    val createdByUserId: Long? = null,
    val updatedAt: Instant? = null,
    val updatedByUserId: Long? = null,
    val currentVersion: Int,
    val currentBytes: Long,
    val isLive: Boolean = false,
)

@Table("html_page_versions")
data class HtmlPageVersionEntity(
    @Id val id: Long? = null,
    val pageId: String,
    val version: Int,
    val html: String,
    val bytes: Long,
    val createdAt: Instant,
    val createdByUserId: Long? = null,
)
