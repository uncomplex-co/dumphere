package dev.uncomplex.htmlshare.htmlshare

import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

@RestController
class HtmlPageController(
    private val store: HtmlPageStore,
) {
    @GetMapping("/p/{id}")
    fun show(@PathVariable id: String): ResponseEntity<String> {
        val html = store.readHtml(id) ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
            .header("Content-Security-Policy", "default-src 'none'; img-src data: https:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'none'; base-uri 'none'; form-action 'none'")
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
            .body(html)
    }

    @GetMapping("/api/pages/{id}")
    fun metadata(@PathVariable id: String): ResponseEntity<PublishedPage> {
        val page = store.readMetadata(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(page)
    }

    @PutMapping("/api/pages/{id}")
    fun update(
        @PathVariable id: String,
        @RequestBody request: UpdatePageRequest,
        authentication: Authentication,
    ): ResponseEntity<PublishedPage> {
        val page = store.update(id, request.title, request.html, authentication.authenticatedUser()) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(page)
    }
}

data class UpdatePageRequest(
    val html: String,
    val title: String? = null,
)
