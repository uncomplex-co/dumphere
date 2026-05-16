package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint

class OAuthMetadataEntryPoint(
    private val properties: DumpHereApplicationProperties,
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
