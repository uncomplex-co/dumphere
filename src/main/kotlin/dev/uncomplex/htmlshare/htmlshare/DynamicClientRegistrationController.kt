package dev.uncomplex.htmlshare.htmlshare

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.oidc.OidcScopes
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RestController
class DynamicClientRegistrationController(
    private val clients: RegisteredClientRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    @PostMapping("/connect/register")
    fun register(@RequestBody request: DynamicClientRegistrationRequest): DynamicClientRegistrationResponse {
        val clientId = UUID.randomUUID().toString()
        val clientSecret = UUID.randomUUID().toString()
        val scopes = request.scope?.split(' ')?.filter { it.isNotBlank() }?.toSet().orEmpty()
            .ifEmpty { setOf(OidcScopes.OPENID, OidcScopes.EMAIL, OidcScopes.PROFILE) }

        val client = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(clientId)
            .clientIdIssuedAt(Instant.now())
            .clientSecret(passwordEncoder.encode(clientSecret))
            .clientName(request.clientName ?: "opencode")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUris { it.addAll(request.redirectUris) }
            .scopes { it.addAll(scopes) }
            .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).requireProofKey(true).build())
            .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).reuseRefreshTokens(false).build())
            .build()

        clients.save(client)

        return DynamicClientRegistrationResponse(
            clientId = clientId,
            clientSecret = clientSecret,
            clientName = client.clientName,
            redirectUris = request.redirectUris,
            grantTypes = listOf("authorization_code"),
            tokenEndpointAuthMethod = "client_secret_basic",
            scope = scopes.joinToString(" "),
        )
    }
}

data class DynamicClientRegistrationRequest(
    @JsonProperty("client_name") val clientName: String? = null,
    @JsonProperty("redirect_uris") val redirectUris: List<String> = emptyList(),
    @JsonProperty("grant_types") val grantTypes: List<String> = emptyList(),
    val scope: String? = null,
)

data class DynamicClientRegistrationResponse(
    @JsonProperty("client_id") val clientId: String,
    @JsonProperty("client_secret") val clientSecret: String,
    @JsonProperty("client_name") val clientName: String,
    @JsonProperty("redirect_uris") val redirectUris: List<String>,
    @JsonProperty("grant_types") val grantTypes: List<String>,
    @JsonProperty("token_endpoint_auth_method") val tokenEndpointAuthMethod: String,
    val scope: String,
)
