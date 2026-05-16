package dev.uncomplex.htmlshare.htmlshare

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint

class OAuthMetadataEntryPoint(
    private val properties: HtmlshareProperties,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val metadataUrl = "${properties.publicBaseUrl.trimEnd('/')}/.well-known/oauth-protected-resource"

        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"$metadataUrl\"")
    }
}
