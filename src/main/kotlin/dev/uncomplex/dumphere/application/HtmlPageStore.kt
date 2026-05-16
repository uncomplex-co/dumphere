package dev.uncomplex.dumphere.application

import com.fasterxml.jackson.databind.ObjectMapper
import dev.uncomplex.dumphere.ports.HtmlPageRepository
import dev.uncomplex.dumphere.ports.HtmlPageVersionRepository
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.inputStream

@Service
class HtmlPageStore(
    private val properties: DumpHereApplicationProperties,
    private val objectMapper: ObjectMapper,
    private val pages: HtmlPageRepository,
    private val versions: HtmlPageVersionRepository,
    private val userProvisioning: UserProvisioningService,
    private val jdbcAggregateTemplate: JdbcAggregateTemplate,
) {
    private val random = SecureRandom()
    private val storageDir: Path = Path.of(properties.storageDir).toAbsolutePath().normalize()

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
                version = 1,
                bytes = bytes.size.toLong(),
            )

        jdbcAggregateTemplate.insert(page.toEntity(createdByUserId = user?.id))
        versions.save(page.toVersionEntity(contents, page.createdAt, user?.id))

        return page
    }

    fun readContents(id: String): String? {
        if (!id.matches(ID_PATTERN)) return null

        val page = pages.findById(id).orElse(null) ?: return readLegacyHtml(id)
        return versions.findByPageIdAndVersion(id, page.currentVersion)?.html ?: readLegacyHtml(id)
    }

    fun readHtml(id: String): String? = readContents(id)

    @Transactional
    fun update(
        id: String,
        contents: String,
        updatedBy: AuthenticatedUser?,
    ): PublishedPage? {
        if (!id.matches(ID_PATTERN)) return null

        val existingEntity = pages.findById(id).orElse(null)
        val existing = existingEntity?.toPublishedPage() ?: readLegacyMetadata(id) ?: return null
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
        pages.save(page.toEntity(createdByUserId = existingEntity?.createdByUserId, updatedByUserId = user?.id))

        return page
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

        return pages.findById(id).map { it.toPublishedPage() }.orElseGet { readLegacyMetadata(id) }
    }

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
            updatedAt = updatedAt,
            version = currentVersion,
            bytes = currentBytes,
        )

    private fun readLegacyHtml(id: String): String? {
        val path = legacyHtmlPath(id)
        if (!path.exists()) return null

        return Files.readString(path, StandardCharsets.UTF_8)
    }

    private fun readLegacyMetadata(id: String): PublishedPage? {
        val path = metadataPath(id)
        if (!path.exists()) return null

        return path.inputStream().use { objectMapper.readValue(it, PublishedPage::class.java) }
    }

    private fun newId(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun legacyHtmlPath(id: String) = storageDir.resolve("$id.html")

    private fun metadataPath(id: String) = storageDir.resolve("$id.json")

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{16,64}")
    }
}
