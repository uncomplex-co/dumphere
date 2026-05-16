package dev.uncomplex.dumphere.application

import java.time.Instant

data class PublishedPage(
    val id: String,
    val title: String,
    val url: String,
    val createdAt: Instant,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val version: Int = 1,
    val bytes: Long,
)
