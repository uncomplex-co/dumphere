package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.application.authenticatedUser
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import kotlin.text.iterator

@Controller
class ConsentController(
    private val mcpOAuthService: McpOAuthService,
) {
    @GetMapping("/oauth2/consent", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun consent(
        @RequestParam("state") state: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String {
        val authorizationRequest = request.session.getAttribute(McpAuthorizationRequest.sessionKey(state)) as? McpAuthorizationRequest
        if (authorizationRequest == null) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return "Missing pending MCP authorization request."
        }
        val csrf = request.getAttribute(CsrfToken::class.java.name) as? CsrfToken

        return """
                        <!doctype html>
                        <html lang="en">
                        <head>
                          <meta charset="utf-8">
                          <meta name="viewport" content="width=device-width, initial-scale=1">
                          <title>Approve htmlshare access</title>
                          <style>
                            body { margin: 0; min-height: 100vh; display: grid; place-items: center; font-family: Inter, system-ui, sans-serif; background: #f5f4ee; color: #111; }
                            main { width: min(480px, calc(100% - 32px)); padding: 32px; border: 1px solid rgba(0,0,0,.12); border-radius: 28px; background: white; box-shadow: 0 24px 70px rgba(0,0,0,.08); }
                            h1 { margin: 0 0 10px; font-size: 34px; line-height: .95; letter-spacing: -.05em; }
                            p { margin: 0 0 20px; color: #666; line-height: 1.45; font-weight: 600; }
                            .permission { margin: 20px 0; padding: 14px 16px; border-radius: 18px; background: #f2f2ed; color: #555; line-height: 1.4; font-weight: 700; }
                            .actions { display: flex; gap: 10px; }
                            button { flex: 1; min-height: 48px; border: 0; border-radius: 999px; font-weight: 900; cursor: pointer; }
                            .approve { background: #111; color: white; }
                            .deny { background: #eee; color: #111; }
                          </style>
                        </head>
                        <body>
                          <main>
                            <h1>Approve MCP access</h1>
                            <p>Allow ${authorizationRequest.clientName.escapeHtml()} to publish and update shared HTML pages as you.</p>
                            <form method="post" action="/oauth2/consent">
                              <input type="hidden" name="state" value="${state.escapeHtml()}">
                              ${csrf?.let {
            """<input type="hidden" name="${it.parameterName.escapeHtml()}" value="${it.token.escapeHtml()}">"""
        } ?: ""}
                              <div class="permission">This grants htmlshare access to confirm your identity and record your email/name on pages you create or update.</div>
                              <div class="actions">
                                <button class="approve" type="submit" name="decision" value="approve">Approve</button>
                                <button class="deny" type="submit" name="decision" value="deny">Deny</button>
                              </div>
                            </form>
                          </main>
                        </body>
                        </html>
            """.trimIndent()
    }

    @PostMapping("/oauth2/consent")
    fun decide(
        @RequestParam("state") state: String,
        @RequestParam("decision") decision: String,
        authentication: Authentication,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val session = request.session
        val authorizationRequest = session.getAttribute(McpAuthorizationRequest.sessionKey(state)) as? McpAuthorizationRequest
        session.removeAttribute(McpAuthorizationRequest.sessionKey(state))

        if (authorizationRequest == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing pending MCP authorization request")
            return
        }

        if (decision != "approve") {
            response.sendRedirect(mcpOAuthService.authorizationErrorRedirect(authorizationRequest, "access_denied"))
            return
        }

        val user = authentication.authenticatedUser()
        if (user == null) {
            response.sendRedirect("/login")
            return
        }

        val code = mcpOAuthService.issueAuthorizationCode(authorizationRequest, user)
        response.sendRedirect(mcpOAuthService.authorizationSuccessRedirect(authorizationRequest, code))
    }

    private fun String.escapeHtml(): String =
        buildString(length) {
            for (char in this@escapeHtml) {
                append(
                    when (char) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&#39;"
                        else -> char
                    },
                )
            }
        }
}
