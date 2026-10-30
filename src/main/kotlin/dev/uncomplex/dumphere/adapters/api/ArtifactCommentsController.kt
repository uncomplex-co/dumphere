package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.adapters.ReactionEmojiCatalog

import dev.uncomplex.dumphere.application.ArtifactComment
import dev.uncomplex.dumphere.application.ArtifactComments
import dev.uncomplex.dumphere.application.EditCommentResult
import dev.uncomplex.dumphere.application.DeleteCommentResult
import dev.uncomplex.dumphere.application.DiscussionThreadState
import dev.uncomplex.dumphere.application.CommentReactionState
import dev.uncomplex.dumphere.application.SetCommentReactionUsecase
import dev.uncomplex.dumphere.adapters.authentication.AuthenticatedUsers
import dev.uncomplex.dumphere.ports.PublishedArtifactsStore
import dev.uncomplex.dumphere.ports.CommentThreadRepository
import dev.uncomplex.dumphere.ports.HtmlPageVersionRepository
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import javax.xml.xpath.XPathExpressionException
import javax.xml.xpath.XPathFactory

@RestController
class ArtifactCommentsController(
    private val comments: ArtifactComments,
    private val pages: PublishedArtifactsStore,
    private val versions: HtmlPageVersionRepository,
    private val access: ArtifactAccess,
    private val threads: CommentThreadRepository,
    private val setReaction: SetCommentReactionUsecase,
    private val emojiCatalog: ReactionEmojiCatalog,
    private val users: AuthenticatedUsers,
) {
    @GetMapping("/p/{id}/comments")
    fun list(
        @PathVariable id: String,
        authentication: Authentication?,
    ): ResponseEntity<List<ArtifactComment>> {
        requireAccess(id, authentication)
        val viewer = resolveUser(authentication)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(comments.list(id, viewer))
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
        val author = resolveUser(authentication)
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
            threads.findById(threadId).orElse(null)?.takeIf { it.artifactId == id && it.deletedAt == null }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val version =
            versions.findByPageIdAndVersion(id, request.version)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Artifact version not found")
        val author = resolveUser(authentication)
        val comment = comments.reply(thread, requireNotNull(version.id), body, author)
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(comment)
    }

    @PutMapping("/p/{id}/comments/{commentId}/reaction")
    fun react(
        @PathVariable id: String,
        @PathVariable commentId: Long,
        @RequestBody request: SetCommentReactionRequest,
        authentication: Authentication?,
    ): ResponseEntity<CommentReactionState> {
        requireAccess(id, authentication)
        val author = resolveUser(authentication)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        if (!emojiCatalog.contains(request.emoji)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported reaction emoji")
        return when (val result = setReaction.execute(SetCommentReactionUsecase.Command(id, commentId, request.emoji, request.reacted, author))) {
            is SetCommentReactionUsecase.Result.Success -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.state)
            SetCommentReactionUsecase.Result.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    @PatchMapping("/p/{id}/comments/{commentId}")
    fun edit(
        @PathVariable id: String,
        @PathVariable commentId: Long,
        @RequestBody request: EditArtifactCommentRequest,
        authentication: Authentication?,
    ): ResponseEntity<ArtifactComment> {
        requireAccess(id, authentication)
        val author = resolveUser(authentication)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        return when (val result = comments.edit(id, commentId, validatedBody(request.body), author)) {
            is EditCommentResult.Updated -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.comment)
            EditCommentResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            EditCommentResult.Forbidden -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }

    @DeleteMapping("/p/{id}/comments/{commentId}")
    fun delete(
        @PathVariable id: String,
        @PathVariable commentId: Long,
        authentication: Authentication?,
    ): ResponseEntity<Void> {
        requireAccess(id, authentication)
        val author = resolveUser(authentication)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        return when (comments.delete(id, commentId, author)) {
            DeleteCommentResult.DELETED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
            DeleteCommentResult.NOT_FOUND -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            DeleteCommentResult.FORBIDDEN -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }

    @PatchMapping("/p/{id}/threads/{threadId}/resolution")
    fun setResolved(
        @PathVariable id: String,
        @PathVariable threadId: Long,
        @RequestBody request: SetThreadResolutionRequest,
        authentication: Authentication?,
    ): ResponseEntity<DiscussionThreadState> {
        requireAccess(id, authentication)
        val author = resolveUser(authentication)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val state = comments.setResolved(id, threadId, request.resolved, author)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(state)
    }

    private fun resolveUser(authentication: Authentication?) =
        users.resolve(authentication.takeIf { access.isMember(it) })

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

data class EditArtifactCommentRequest(val body: String)

data class SetThreadResolutionRequest(val resolved: Boolean)

data class SetCommentReactionRequest(val emoji: String, val reacted: Boolean)
