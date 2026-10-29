package dev.uncomplex.dumphere.application

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

data class PublishedPage(
    val id: String,
    val title: String,
    val url: String,
    val contentFormat: PageContentFormat = PageContentFormat.HTML,
    val createdAt: Instant,
    val createdBy: ArtifactAuthor? = null,
    val updatedAt: Instant? = null,
    val updatedBy: ArtifactAuthor? = null,
    val version: Int = 1,
    val bytes: Long,
    @get:JsonProperty("live")
    val isLive: Boolean = false,
    val visibility: ArtifactSharing = ArtifactSharing(),
    val customMetadata: Map<String, Any> = emptyMap(),
)

data class ArtifactAuthor(val email: String?, val name: String?)

data class ArtifactSharing(val publicUntil: Instant? = null) {
    val visibility: ArtifactVisibility
        get() = if (publicUntil?.isAfter(Instant.now()) == true) ArtifactVisibility.PUBLIC else ArtifactVisibility.INTERNAL
}
