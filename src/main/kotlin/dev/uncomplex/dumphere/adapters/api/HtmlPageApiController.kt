package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.HtmlPageStore
import dev.uncomplex.dumphere.application.PageContentFormat
import dev.uncomplex.dumphere.application.PublishedPage
import dev.uncomplex.dumphere.application.authenticatedUser
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class HtmlPageApiController(
    private val store: HtmlPageStore,
) {
    @GetMapping("/api/pages/{id}")
    fun metadata(
        @PathVariable id: String,
    ): PublishedPage = store.readMetadata(id) ?: throw notFound()

    @GetMapping("/api/pages/{id}/version")
    fun version(
        @PathVariable id: String,
    ): Map<String, Int> {
        val page = store.readMetadata(id) ?: throw notFound()
        return mapOf("version" to page.version)
    }

    @PostMapping("/api/pages")
    fun publish(
        @RequestBody request: PublishFileRequest,
        authentication: Authentication,
    ): PublishFileResponse {
        val contentFormat = request.mimeType.toPageContentFormat()
        val page = store.publish(request.title, request.content, contentFormat, authentication.authenticatedUser())

        return PublishFileResponse(page.url, page.version)
    }

    @PutMapping("/api/pages/{id}")
    fun update(
        @PathVariable id: String,
        @RequestBody request: UpdatePageRequest,
        authentication: Authentication,
    ): PublishedPage = store.update(id, request.html, authentication.authenticatedUser()) ?: throw notFound()

    @PostMapping("/api/pages/{id}/live")
    fun setLive(
        @PathVariable id: String,
        @RequestBody request: SetLiveRequest,
        authentication: Authentication,
    ): PublishedPage {
        val page = store.setLive(id, request.live, authentication.authenticatedUser())
            ?: throw notFound()
        return page
    }
}

data class UpdatePageRequest(
    val html: String,
)

data class PublishFileRequest(
    val title: String,
    val content: String,
    val mimeType: String,
)

data class PublishFileResponse(
    val url: String,
    val version: Int,
)

data class SetLiveRequest(
    val live: Boolean,
)

private fun String.toPageContentFormat(): PageContentFormat =
    when (trim().lowercase().substringBefore(';')) {
        "text/html", "application/xhtml+xml" -> PageContentFormat.HTML
        "text/markdown", "text/x-markdown" -> PageContentFormat.MARKDOWN
        else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported mimeType")
    }

private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND)
