package dev.uncomplex.dumphere.adapters.mcp

import com.nimbusds.jose.jwk.JWKSet
import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class OAuthMetadataController(
    private val properties: DumpHereApplicationProperties,
    private val jwkSet: JWKSet,
) {
    @GetMapping("/.well-known/oauth-protected-resource")
    fun protectedResourceMetadata(): Map<String, Any> {
        val baseUrl = properties.publicBaseUrl.trimEnd('/')
        val resource = "$baseUrl/mcp"

        return mapOf(
            "resource" to resource,
            "authorization_servers" to listOf(baseUrl),
            "scopes_supported" to listOf("openid", "email", "profile"),
            "bearer_methods_supported" to listOf("header"),
        )
    }

    @GetMapping("/.well-known/oauth-authorization-server")
    fun authorizationServerMetadata(): Map<String, Any> {
        val baseUrl = properties.publicBaseUrl.trimEnd('/')

        return mapOf(
            "issuer" to baseUrl,
            "authorization_endpoint" to "$baseUrl/oauth2/authorize",
            "token_endpoint" to "$baseUrl/oauth2/token",
            "jwks_uri" to "$baseUrl/oauth2/jwks",
            "registration_endpoint" to "$baseUrl/connect/register",
            "grant_types_supported" to listOf("authorization_code", "refresh_token"),
            "response_types_supported" to listOf("code"),
            "token_endpoint_auth_methods_supported" to listOf("client_secret_basic", "client_secret_post"),
            "code_challenge_methods_supported" to listOf("S256"),
            "scopes_supported" to listOf("openid", "email", "profile"),
        )
    }

    @GetMapping("/.well-known/openid-configuration")
    fun openidConfiguration(): Map<String, Any> = authorizationServerMetadata()

    @GetMapping("/oauth2/jwks")
    fun jwks(): Map<String, Any> = jwkSet.toJSONObject()
}
