package dev.uncomplex.dumphere.application

import dev.uncomplex.dumphere.ports.HtmlPageRepository
import dev.uncomplex.dumphere.ports.HtmlPageVersionRepository
import dev.uncomplex.dumphere.ports.UserRepository
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

@Service
class HtmlPageStore(
    private val properties: SamePageAppProperties,
    private val objectMapper: ObjectMapper,
    private val pages: HtmlPageRepository,
    private val versions: HtmlPageVersionRepository,
    private val jdbcAggregateTemplate: JdbcAggregateTemplate,
    private val users: UserRepository,
) {
    private val random = SecureRandom()

    @Transactional
    fun publish(
        title: String?,
        contents: String,
        contentFormat: PageContentFormat,
        createdBy: UserEntity?,
    ): PublishedPage {
        val bytes = contents.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty()) { "contents must not be empty" }
        require(bytes.size <= properties.maxHtmlBytes) {
            "contents are ${bytes.size} bytes, max is ${properties.maxHtmlBytes}"
        }

        val id = newId()
        val now = Instant.now()
        val user = createdBy
        val page =
            PublishedPage(
                id = id,
                title = title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled HTML page",
                url = "${properties.publicBaseUrl.trimEnd('/')}/p/$id",
                contentFormat = contentFormat,
                createdAt = now,
                createdBy = user?.let { ArtifactAuthor(it.email, it.displayName) },
                version = 1,
                bytes = bytes.size.toLong(),
            )

        jdbcAggregateTemplate.insert(page.toEntity(createdByUserId = user?.id))
        versions.save(page.toVersionEntity(contents, page.createdAt, user?.id))

        return page
    }

    fun readContents(id: String): String? {
        if (!id.matches(ID_PATTERN)) return null

        val page = pages.findById(id).orElse(null) ?: return null
        return versions.findByPageIdAndVersion(id, page.currentVersion)?.html
    }

    @Transactional
    fun update(
        id: String,
        contents: String,
        updatedBy: UserEntity?,
    ): PublishedPage? {
        if (!id.matches(ID_PATTERN)) return null

        val existingEntity = pages.findByIdForUpdate(id) ?: return null
        val bytes = contents.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty()) { "contents must not be empty" }
        require(bytes.size <= properties.maxHtmlBytes) {
            "contents are ${bytes.size} bytes, max is ${properties.maxHtmlBytes}"
        }

        val now = Instant.now()
        existingEntity.updatedAt = now
        existingEntity.updatedByUserId = updatedBy?.id
        existingEntity.currentVersion += 1
        existingEntity.currentBytes = bytes.size.toLong()

        versions.save(existingEntity.toPublishedPage().toVersionEntity(contents, now, updatedBy?.id))
        val updated = pages.save(existingEntity)

        return updated.toPublishedPage()
    }

    @Transactional
    fun edit(
        id: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
        updatedBy: UserEntity?,
    ): PublishedPage? {
        if (!id.matches(ID_PATTERN)) return null

        val currentContents = readContents(id) ?: return null
        val nextContents =
            if (oldString.isEmpty()) {
                require(oldString != newString) {
                    "No changes to apply: oldString and newString are identical."
                }
                newString
            } else {
                HtmlDocumentEditor.applyWithOriginalLineEndings(currentContents, oldString, newString, replaceAll)
            }

        return update(id, nextContents, updatedBy)
    }

    fun readMetadata(id: String): PublishedPage? {
        if (!id.matches(ID_PATTERN)) return null

        return pages.findById(id).map { it.toPublishedPage() }.orElse(null)
    }

    @Transactional
    fun setLive(
        id: String,
        isLive: Boolean,
        updatedBy: UserEntity?,
    ): PublishedPage? {
        if (!id.matches(ID_PATTERN)) return null

        val existingEntity = pages.findByIdForUpdate(id) ?: return null
        existingEntity.isLive = isLive
        existingEntity.updatedAt = Instant.now()
        existingEntity.updatedByUserId = updatedBy?.id
        pages.save(existingEntity)
        return existingEntity.toPublishedPage()
    }

    @Transactional
    fun setVisibility(
        id: String,
        visibility: ArtifactVisibility,
        updatedBy: UserEntity?,
    ): PublishedPage? {
        if (!id.matches(ID_PATTERN)) return null

        val existing = pages.findByIdForUpdate(id) ?: return null
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val publicUntil =
            when (visibility) {
                ArtifactVisibility.INTERNAL -> null
                ArtifactVisibility.PUBLIC -> existing.publicUntil?.takeIf { it.isAfter(now) } ?: now.plus(Duration.ofDays(30))
            }
        if (publicUntil == existing.publicUntil) return existing.toPublishedPage()

        existing.publicUntil = publicUntil
        existing.updatedAt = now
        existing.updatedByUserId = updatedBy?.id
        pages.save(existing)
        return existing.toPublishedPage()
    }

    @Transactional
    fun setCustomMetadata(
        id: String,
        key: String,
        value: Any?,
        updatedBy: UserEntity?,
    ): PublishedPage? {
        if (!id.matches(ID_PATTERN)) return null
        val existing = pages.findByIdForUpdate(id) ?: return null
        val metadata = readCustomMetadata(id).toMutableMap()
        if (value == null) metadata.remove(key) else metadata[key] = value
        val now = Instant.now()
        pages.updateCustomMetadata(id, objectMapper.writeValueAsString(metadata), now, updatedBy?.id)
        existing.updatedAt = now
        existing.updatedByUserId = updatedBy?.id
        return existing.toPublishedPage()
    }

    private fun readCustomMetadata(id: String): Map<String, Any> =
        objectMapper.readValue(requireNotNull(pages.readCustomMetadata(id)), object : TypeReference<Map<String, Any>>() {})

    private fun PublishedPage.toEntity(
        createdByUserId: Long? = null,
        updatedByUserId: Long? = null,
    ) = HtmlPageEntity(
        id = id,
        title = title,
        url = url,
        contentFormat = contentFormat,
        createdAt = createdAt,
        createdByUserId = createdByUserId,
        updatedAt = updatedAt,
        updatedByUserId = updatedByUserId,
        currentVersion = version,
        currentBytes = bytes,
        isLive = isLive,
        publicUntil = visibility.publicUntil,
    )

    private fun PublishedPage.toVersionEntity(
        contents: String,
        recordedAt: Instant,
        createdByUserId: Long?,
    ) = HtmlPageVersionEntity(
        pageId = id,
        version = version,
        html = contents,
        bytes = bytes,
        createdAt = recordedAt,
        createdByUserId = createdByUserId,
    )

    private fun HtmlPageEntity.toPublishedPage() =
        PublishedPage(
            id = id,
            title = title,
            url = url,
            contentFormat = contentFormat,
            createdAt = createdAt,
            createdBy = author(createdByUserId),
            updatedAt = updatedAt,
            updatedBy = author(updatedByUserId),
            version = currentVersion,
            bytes = currentBytes,
            isLive = isLive,
            visibility = ArtifactSharing(publicUntil),
            customMetadata = readCustomMetadata(id),
        )

    private fun author(userId: Long?): ArtifactAuthor? =
        userId?.let { id ->
            users.findById(id).orElse(null)?.let { ArtifactAuthor(it.email, it.displayName) }
        }

    private fun newId(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{16,64}")
    }
}
