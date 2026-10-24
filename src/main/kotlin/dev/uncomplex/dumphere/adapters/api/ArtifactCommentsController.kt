package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.ArtifactComment
import dev.uncomplex.dumphere.application.ArtifactComments
import dev.uncomplex.dumphere.application.HtmlPageStore
import dev.uncomplex.dumphere.application.authenticatedUser
import dev.uncomplex.dumphere.ports.CommentThreadRepository
import dev.uncomplex.dumphere.ports.HtmlPageVersionRepository
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import javax.xml.xpath.XPathExpressionException
import javax.xml.xpath.XPathFactory

@RestController
class ArtifactCommentsController(
    private val comments: ArtifactComments,
    private val pages: HtmlPageStore,
    private val versions: HtmlPageVersionRepository,
    private val access: ArtifactAccess,
    private val threads: CommentThreadRepository,
) {
    @GetMapping("/p/{id}/comments")
    fun list(
        @PathVariable id: String,
        authentication: Authentication?,
    ): ResponseEntity<List<ArtifactComment>> {
        requireAccess(id, authentication)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(comments.list(id))
    }

    @PostMapping("/p/{id}/comments")
    fun add(
        @PathVariable id: String,
        @RequestBody request: AddArtifactCommentRequest,
        authentication: Authentication?,
    ): ResponseEntity<ArtifactComment> {
        requireAccess(id, authentication)
        val body = validatedBody(request.body)
        if (request.xpath == null) {
            if (request.relativeX != null || request.relativeY != null) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Coordinates require an element XPath")
            }
        } else if (request.relativeX == null || request.relativeY == null ||
            request.relativeX !in 0.0..1.0 || request.relativeY !in 0.0..1.0
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Relative coordinates must be between 0 and 1")
        }
        request.xpath?.let { xpath ->
            if (!xpath.startsWith('/') || xpath.length > 4096) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid element XPath")
            }
            try {
                XPathFactory.newInstance().newXPath().compile(xpath)
            } catch (_: XPathExpressionException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid element XPath")
            }
        }
        val version =
            versions.findByPageIdAndVersion(id, request.version)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Artifact version not found")
        val author = authentication.takeIf { access.isMember(it) }.authenticatedUser()
        val comment = comments.add(id, requireNotNull(version.id), body, author, request.xpath, request.relativeX, request.relativeY)
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(comment)
    }

    @PostMapping("/p/{id}/threads/{threadId}/comments")
    fun reply(
        @PathVariable id: String,
        @PathVariable threadId: Long,
        @RequestBody request: ReplyToThreadRequest,
        authentication: Authentication?,
    ): ResponseEntity<ArtifactComment> {
        requireAccess(id, authentication)
        val body = validatedBody(request.body)
        val thread =
            threads.findById(threadId).orElse(null)?.takeIf { it.artifactId == id }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val version =
            versions.findByPageIdAndVersion(id, request.version)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Artifact version not found")
        val author = authentication.takeIf { access.isMember(it) }.authenticatedUser()
        val comment = comments.reply(thread, requireNotNull(version.id), body, author)
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(comment)
    }

    private fun validatedBody(value: String): String {
        val body = value.trim()
        if (body.isEmpty() || body.length > 10000) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment must contain between 1 and 10000 characters")
        }
        return body
    }

    private fun requireAccess(
        id: String,
        authentication: Authentication?,
    ) {
        val page = pages.readMetadata(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (!access.canView(page, authentication)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }
}

data class AddArtifactCommentRequest(
    val body: String,
    val version: Int,
    val xpath: String? = null,
    val relativeX: Double? = null,
    val relativeY: Double? = null,
)

data class ReplyToThreadRequest(
    val body: String,
    val version: Int,
)
