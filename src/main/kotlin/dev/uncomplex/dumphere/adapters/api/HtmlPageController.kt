package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.HtmlPageStore
import dev.uncomplex.dumphere.application.PageContentFormat
import dev.uncomplex.dumphere.application.PageContentRenderer
import dev.uncomplex.dumphere.application.PublishedPage
import dev.uncomplex.dumphere.application.SamePageAppProperties
import dev.uncomplex.dumphere.application.authenticatedUser
import dev.uncomplex.dumphere.application.UserProvisioningService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets.UTF_8

@RestController
class HtmlPageController(
    private val store: HtmlPageStore,
    private val renderer: PageContentRenderer,
    private val properties: SamePageAppProperties,
    private val access: ArtifactAccess,
    private val viewer: ArtifactViewer,
    private val users: UserProvisioningService,
) {
    private val viewerSecurityPolicy =
        listOf(
            "default-src 'none'",
            "script-src 'self'",
            "style-src 'self' 'sha256-I3QIFHoQipvvv7VFJz5JKfJGG0vZdYBUqM1UqySC14g='",
            "img-src 'self' https:",
            "frame-src 'self'",
            "connect-src 'self'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'self'",
        ).joinToString("; ")

    private val contentSecurityPolicy =
        listOf(
            "sandbox allow-scripts allow-same-origin",
            "default-src 'none'",
            "img-src data: https:",
            "style-src ${properties.cspStyleSrc}",
            "script-src ${properties.cspScriptSrc}",
            "font-src ${properties.cspFontSrc}",
            "connect-src 'none'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'self'",
        ).joinToString("; ")

    private val wrapperSecurityPolicy =
        listOf(
            "default-src 'none'",
            "script-src 'self'",
            "style-src 'self'",
            "frame-src 'self'",
            "connect-src 'none'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'self'",
        ).joinToString("; ")

    @GetMapping("/p/{id}", produces = [MediaType.TEXT_HTML_VALUE])
    fun show(
        @PathVariable id: String,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        val page = store.readMetadata(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val canView = access.canView(page, authentication)
        val canManage = access.isMember(authentication)
        val csrf = if (canView) (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token.orEmpty() else ""
        return ResponseEntity
            .status(if (canView) HttpStatus.OK else HttpStatus.FORBIDDEN)
            .cacheControl(CacheControl.noStore())
            .header("Content-Security-Policy", viewerSecurityPolicy)
            .header("Referrer-Policy", "no-referrer")
            .body(viewer.render(page.takeIf { canView }, authentication, canManage, csrf))
    }

    @GetMapping("/p/{id}/wrapper", produces = [MediaType.TEXT_HTML_VALUE])
    fun wrapper(
        @PathVariable id: String,
        authentication: Authentication?,
    ): ResponseEntity<String> {
        val page = readable(id, authentication)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .header("Content-Security-Policy", wrapperSecurityPolicy)
            .header("Referrer-Policy", "no-referrer")
            .body(viewer.renderWrapper(page))
    }

    @GetMapping("/p/{id}/content")
    fun content(
        @PathVariable id: String,
        authentication: Authentication?,
    ): ResponseEntity<String> {
        val page = readable(id, authentication)
        val contents = store.readContents(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_HTML)
            .cacheControl(CacheControl.noStore())
            .header("Content-Security-Policy", contentSecurityPolicy)
            .header("Referrer-Policy", "no-referrer")
            .body(renderer.render(contents, page.contentFormat, page.title))
    }

    @GetMapping("/p/{id}/download")
    fun download(
        @PathVariable id: String,
        authentication: Authentication?,
    ): ResponseEntity<ByteArray> {
        val page = readable(id, authentication)
        val contents = store.readContents(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val extension = if (page.contentFormat == PageContentFormat.MARKDOWN) ".md" else ".html"
        val name =
            page.title
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[\\p{C}<>:\"|?*]"), "")
                .replace(Regex("\\.(html?|md|markdown)$", RegexOption.IGNORE_CASE), "")
                .trim()
                .take(120)
                .ifBlank { "artifact" } + extension
        return ResponseEntity
            .ok()
            .contentType(MediaType("text", if (page.contentFormat == PageContentFormat.MARKDOWN) "markdown" else "html", UTF_8))
            .cacheControl(CacheControl.noStore())
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(name, UTF_8)
                    .build()
                    .toString(),
            ).header("Content-Security-Policy", "sandbox; default-src 'none'")
            .body(contents.toByteArray(UTF_8))
    }

    @GetMapping("/p/{id}/status")
    fun status(
        @PathVariable id: String,
        authentication: Authentication?,
    ): ResponseEntity<ViewerStatus> {
        val page = readable(id, authentication)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ViewerStatus(page, access.isMember(authentication)))
    }

    @PostMapping("/p/{id}/visibility")
    fun visibility(
        @PathVariable id: String,
        @RequestBody request: SetVisibilityRequest,
        authentication: Authentication?,
    ): ResponseEntity<PublishedPage> {
        if (!access.isMember(authentication)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val page =
            store.setVisibility(id, request.visibility, authentication.authenticatedUser()?.let(users::provision))
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }

    private fun readable(
        id: String,
        authentication: Authentication?,
    ): PublishedPage {
        val page = store.readMetadata(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (!access.canView(page, authentication)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        return page
    }
}

data class ViewerStatus(
    val artifact: PublishedPage,
    val canManage: Boolean,
)
