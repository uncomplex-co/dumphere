package dev.uncomplex.dumphere.adapters.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.util.UUID

object RedirectUrlSupport {
    private const val REDIRECT_URL_PARAMETER = "redirectUrl"
    private const val SESSION_PREFIX = "oauth2.redirect-url."

    fun currentRequestUrl(request: HttpServletRequest): String =
        request.requestURI + request.queryString?.let { "?$it" }.orEmpty()

    fun sanitize(redirectUrl: String?): String? {
        val candidate = redirectUrl?.trim().orEmpty()
        if (candidate.isEmpty()) return null

        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.isAbsolute || uri.host != null || uri.rawPath.isNullOrBlank() || !uri.rawPath.startsWith("/")) return null

        return candidate
    }

    fun loginUrl(redirectUrl: String?): String =
        buildUrl("/login", redirectUrl)

    fun googleAuthorizationUrl(redirectUrl: String?): String =
        buildUrl("/oauth2/authorization/google", redirectUrl)

    fun newStateId(): String = UUID.randomUUID().toString()

    fun remember(session: HttpSession, stateId: String, redirectUrl: String?) {
        val sanitizedRedirectUrl = sanitize(redirectUrl) ?: return
        session.setAttribute(sessionKey(stateId), sanitizedRedirectUrl)
    }

    fun take(session: HttpSession?, stateId: String?): String? {
        if (session == null || stateId.isNullOrBlank()) return null

        val key = sessionKey(stateId)
        val redirectUrl = session.getAttribute(key) as? String
        session.removeAttribute(key)
        return sanitize(redirectUrl)
    }

    private fun buildUrl(path: String, redirectUrl: String?): String {
        val sanitizedRedirectUrl = sanitize(redirectUrl) ?: return path
        return UriComponentsBuilder.fromPath(path).queryParam(REDIRECT_URL_PARAMETER, sanitizedRedirectUrl).build().encode().toUriString()
    }

    private fun sessionKey(stateId: String) = "$SESSION_PREFIX$stateId"
}
