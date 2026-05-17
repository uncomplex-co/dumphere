package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import dev.uncomplex.dumphere.application.HtmlPageStore
import dev.uncomplex.dumphere.application.PageContentRenderer
import dev.uncomplex.dumphere.application.PublishedPage
import dev.uncomplex.dumphere.application.authenticatedUser
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.HtmlUtils
import java.util.concurrent.TimeUnit

@RestController
class HtmlPageController(
    private val store: HtmlPageStore,
    private val renderer: PageContentRenderer,
    private val properties: DumpHereApplicationProperties,
) {
    @GetMapping("/p/{id}")
    fun show(
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val page = store.readMetadata(id) ?: return ResponseEntity.notFound().build()
        val contents = store.readContents(id) ?: return ResponseEntity.notFound().build()

        return if (page.isLive) {
            renderLiveShell(page)
        } else {
            renderPage(page, contents, noCache = false)
        }
    }

    @GetMapping("/p/{id}/content")
    fun content(
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val page = store.readMetadata(id) ?: return ResponseEntity.notFound().build()
        val contents = store.readContents(id) ?: return ResponseEntity.notFound().build()
        return renderPage(page, contents, noCache = true)
    }

    @GetMapping("/api/pages/{id}")
    fun metadata(
        @PathVariable id: String,
    ): ResponseEntity<PublishedPage> {
        val page = store.readMetadata(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(page)
    }

    @GetMapping("/api/pages/{id}/version")
    fun version(
        @PathVariable id: String,
    ): ResponseEntity<Map<String, Int>> {
        val page = store.readMetadata(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(mapOf("version" to page.version))
    }

    @PutMapping("/api/pages/{id}")
    fun update(
        @PathVariable id: String,
        @RequestBody request: UpdatePageRequest,
        authentication: Authentication,
    ): ResponseEntity<PublishedPage> {
        val page = store.update(id, request.html, authentication.authenticatedUser()) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(page)
    }

    @PostMapping("/api/pages/{id}/live")
    fun setLive(
        @PathVariable id: String,
        @RequestBody request: SetLiveRequest,
        authentication: Authentication,
    ): ResponseEntity<PublishedPage> {
        val page = store.setLive(id, request.live, authentication.authenticatedUser())
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(page)
    }

    private fun renderLiveShell(page: PublishedPage): ResponseEntity<String> {
        val escapedTitle = HtmlUtils.htmlEscape(page.title)
        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>$escapedTitle</title>
              <style>body{margin:0;height:100vh;overflow:hidden}</style>
            </head>
            <body>
              <iframe id="content" src="/p/${page.id}/content" style="width:100%;height:100%;border:none;"></iframe>
              <script>
                (function() {
                  var currentVersion = ${page.version};
                  setInterval(function() {
                    fetch('/api/pages/${page.id}/version', { credentials: 'same-origin' })
                      .then(function(r) { return r.json(); })
                      .then(function(data) {
                        if (data.version !== currentVersion) {
                          currentVersion = data.version;
                          document.getElementById('content').src = '/p/${page.id}/content?t=' + Date.now();
                        }
                      })
                      .catch(function() {});
                  }, 10000);
                })();
              </script>
            </body>
            </html>
        """.trimIndent()

        return ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_HTML)
            .cacheControl(CacheControl.noCache())
            .header("Content-Security-Policy", shellCsp())
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
            .body(html)
    }

    private fun renderPage(
        page: PublishedPage,
        contents: String,
        noCache: Boolean,
    ): ResponseEntity<String> {
        var builder = ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_HTML)
            .header("Content-Security-Policy", contentCsp())
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())

        builder = if (noCache) {
            builder.cacheControl(CacheControl.noCache())
        } else {
            builder.cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
        }

        return builder.body(renderer.render(contents, page.contentFormat, page.title))
    }

    private fun shellCsp(): String =
        "default-src 'none'; frame-src 'self'; img-src data: https:; style-src 'unsafe-inline'; script-src ${properties.cspScriptSrc}; connect-src 'self'; base-uri 'none'; form-action 'none'"

    private fun contentCsp(): String =
        "default-src 'none'; img-src data: https:; style-src 'unsafe-inline'; script-src ${properties.cspScriptSrc}; connect-src 'none'; base-uri 'none'; form-action 'none'"
}

data class UpdatePageRequest(
    val html: String,
)

data class SetLiveRequest(
    val live: Boolean,
)
