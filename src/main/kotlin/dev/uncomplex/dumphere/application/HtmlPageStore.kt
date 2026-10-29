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
    private val userProvisioning: UserProvisioningService,
    private val jdbcAggregateTemplate: JdbcAggregateTemplate,
    private val users: UserRepository,
) {
    private val random = SecureRandom()

    @Transactional
    fun publish(
        title: String?,
        contents: String,
        contentFormat: PageContentFormat,
        createdBy: AuthenticatedUser?,
    ): PublishedPage {
        val bytes = contents.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty()) { "contents must not be empty" }
        require(bytes.size <= properties.maxHtmlBytes) {
            "contents are ${bytes.size} bytes, max is ${properties.maxHtmlBytes}"
        }

        val id = newId()
        val now = Instant.now()
        val user = createdBy?.let { userProvisioning.provision(it) }
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
        updatedBy: AuthenticatedUser?,
    ): PublishedPage? {
        if (!id.matches(ID_PATTERN)) return null

        val existingEntity = pages.findByIdForUpdate(id) ?: return null
        val existing = existingEntity.toPublishedPage()
        val bytes = contents.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty()) { "contents must not be empty" }
        require(bytes.size <= properties.maxHtmlBytes) {
            "contents are ${bytes.size} bytes, max is ${properties.maxHtmlBytes}"
        }

        val now = Instant.now()
        val user = updatedBy?.let { userProvisioning.provision(it) }
        val page =
            existing.copy(
                updatedAt = now,
                version = existing.version + 1,
                bytes = bytes.size.toLong(),
            )

        versions.save(page.toVersionEntity(contents, now, user?.id))
        val updated = pages.save(page.toEntity(createdByUserId = existingEntity.createdByUserId, updatedByUserId = user?.id))

        return updated.toPublishedPage()
    }

    @Transactional
    fun edit(
        id: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
        updatedBy: AuthenticatedUser?,
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
        updatedBy: AuthenticatedUser?,
    ): PublishedPage? {
        if (!id.matches(ID_PATTERN)) return null

        val existingEntity = pages.findByIdForUpdate(id) ?: return null
        val user = updatedBy?.let { userProvisioning.provision(it) }
        val updatedEntity =
            existingEntity.copy(
                isLive = isLive,
                updatedAt = Instant.now(),
                updatedByUserId = user?.id,
            )
        pages.save(updatedEntity)
        return updatedEntity.toPublishedPage()
    }

    @Transactional
    fun setVisibility(
        id: String,
        visibility: ArtifactVisibility,
        updatedBy: AuthenticatedUser?,
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

        val user = updatedBy?.let { userProvisioning.provision(it) }
        val updated = existing.copy(publicUntil = publicUntil, updatedAt = now, updatedByUserId = user?.id)
        pages.save(updated)
        return updated.toPublishedPage()
    }

    @Transactional
    fun setCustomMetadata(
        id: String,
        key: String,
        value: Any?,
        updatedBy: AuthenticatedUser?,
    ): PublishedPage? {
        if (!id.matches(ID_PATTERN)) return null
        val existing = pages.findByIdForUpdate(id) ?: return null
        val metadata = readCustomMetadata(id).toMutableMap()
        if (value == null) metadata.remove(key) else metadata[key] = value
        val user = updatedBy?.let(userProvisioning::provision)
        val now = Instant.now()
        pages.updateCustomMetadata(id, objectMapper.writeValueAsString(metadata), now, user?.id)
        return existing.copy(updatedAt = now, updatedByUserId = user?.id).toPublishedPage()
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
