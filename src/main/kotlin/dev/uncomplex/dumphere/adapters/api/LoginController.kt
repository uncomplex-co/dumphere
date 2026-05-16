package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.authenticatedUser
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class LoginController(
    private val requestCache: HttpSessionRequestCache,
) {
    @GetMapping("/login", produces = [MediaType.TEXT_HTML_VALUE])
    fun login(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication?,
    ): String? {
        if (authentication != null && authentication.isAuthenticated && authentication !is AnonymousAuthenticationToken &&
            authentication.authenticatedUser() != null
        ) {
            val savedRequest = requestCache.getRequest(request, response)
            requestCache.removeRequest(request, response)
            response.sendRedirect(savedRequest?.redirectUrl ?: "/login/success")
            return null
        }

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Sign in to htmlshare</title>
              <style>
                body { margin: 0; min-height: 100vh; display: grid; place-items: center; font-family: Inter, system-ui, sans-serif; background: #f5f4ee; color: #111; }
                main { width: min(420px, calc(100% - 32px)); padding: 32px; border: 1px solid rgba(0,0,0,.12); border-radius: 28px; background: white; box-shadow: 0 24px 70px rgba(0,0,0,.08); }
                h1 { margin: 0 0 10px; font-size: 34px; line-height: .95; letter-spacing: -.05em; }
                p { margin: 0 0 24px; color: #666; line-height: 1.45; font-weight: 600; }
                a { display: flex; align-items: center; justify-content: center; min-height: 48px; border-radius: 999px; background: #111; color: white; text-decoration: none; font-weight: 900; }
              </style>
            </head>
            <body>
              <main>
                <h1>Sign in to htmlshare</h1>
                <p>Authenticate before viewing shared pages or publishing HTML.</p>
                <a href="/oauth2/authorization/google">Continue with Google</a>
              </main>
            </body>
            </html>
            """.trimIndent()
    }

    @GetMapping("/login/success", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun success(): String = "Logged in. You can close this tab."
}
