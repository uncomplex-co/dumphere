package dev.uncomplex.dumphere.application

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

data class PublishedPage(
    val id: String,
    val title: String,
    val url: String,
    val contentFormat: PageContentFormat = PageContentFormat.HTML,
    val createdAt: Instant,
    val createdBy: String? = null,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
    val version: Int = 1,
    val bytes: Long,
    @get:JsonProperty("live")
    @param:JsonProperty("live")
    @param:JsonAlias("isLive")
    val isLive: Boolean = false,
    val publicUntil: Instant? = null,
) {
    val visibility: ArtifactVisibility
        get() = if (publicUntil?.isAfter(Instant.now()) == true) ArtifactVisibility.PUBLIC else ArtifactVisibility.INTERNAL
}
