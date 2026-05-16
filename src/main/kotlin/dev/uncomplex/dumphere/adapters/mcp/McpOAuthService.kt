package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.stereotype.Service
import java.io.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.text.Charsets.UTF_8

data class McpAuthorizationRequest(
    val clientId: String,
    val clientName: String,
    val redirectUri: String,
    val state: String,
    val codeChallenge: String,
    val requestedScopes: Set<String>,
) : Serializable {
    companion object {
        fun sessionKey(state: String) = "htmlshare:mcp-auth-request:$state"
    }
}

data class McpTokenResult(
    val accessToken: String,
    val expiresIn: Long,
)

private data class AuthorizationCodeGrant(
    val request: McpAuthorizationRequest,
    val user: AuthenticatedUser,
    val expiresAt: Instant,
)

class OAuthRequestException(
    val error: String,
    override val message: String,
    val redirectUri: String? = null,
) : RuntimeException(message)

class OAuthTokenException(
    val error: String,
    val errorDescription: String,
    val status: HttpStatus = HttpStatus.BAD_REQUEST,
) : RuntimeException(errorDescription)

@Service
class McpOAuthService(
    private val clients: RegisteredClientRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtEncoder: JwtEncoder,
    private val properties: DumpHereApplicationProperties,
) {
    private val grants = ConcurrentHashMap<String, AuthorizationCodeGrant>()
    private val secureRandom = SecureRandom()
    private val codeLifetime = Duration.ofMinutes(2)
    private val tokenLifetime = Duration.ofMinutes(5)

    fun validateAuthorizationRequest(
        responseType: String?,
        clientId: String?,
        redirectUri: String?,
        state: String?,
        codeChallenge: String?,
        codeChallengeMethod: String?,
        scope: String?,
    ): McpAuthorizationRequest {
        val resolvedClientId = clientId?.takeIf { it.isNotBlank() } ?: throw OAuthRequestException("invalid_request", "Missing client_id")
        val client = clients.findByClientId(resolvedClientId) ?: throw OAuthRequestException("invalid_client", "Unknown client_id")
        val resolvedRedirectUri =
            redirectUri?.takeIf { it.isNotBlank() } ?: throw OAuthRequestException("invalid_request", "Missing redirect_uri")

        if (!client.redirectUris.contains(resolvedRedirectUri)) {
            throw OAuthRequestException("invalid_request", "redirect_uri is not registered", resolvedRedirectUri)
        }
        if (responseType != "code") {
            throw OAuthRequestException("unsupported_response_type", "Only authorization code flow is supported", resolvedRedirectUri)
        }

        val resolvedState =
            state?.takeIf { it.isNotBlank() } ?: throw OAuthRequestException("invalid_request", "Missing state", resolvedRedirectUri)
        val resolvedCodeChallenge =
            codeChallenge?.takeIf { it.isNotBlank() }
                ?: throw OAuthRequestException("invalid_request", "Missing code_challenge", resolvedRedirectUri)

        if (codeChallengeMethod != null && codeChallengeMethod != "S256") {
            throw OAuthRequestException("invalid_request", "Only S256 code_challenge_method is supported", resolvedRedirectUri)
        }

        val requestedScopes =
            scope
                .orEmpty()
                .split(' ')
                .filter { it.isNotBlank() }
                .toSet()
                .ifEmpty { client.scopes }

        if (!client.scopes.containsAll(requestedScopes)) {
            throw OAuthRequestException("invalid_scope", "Requested scope is not allowed", resolvedRedirectUri)
        }

        return McpAuthorizationRequest(
            clientId = client.clientId,
            clientName = client.clientName ?: "this MCP client",
            redirectUri = resolvedRedirectUri,
            state = resolvedState,
            codeChallenge = resolvedCodeChallenge,
            requestedScopes = requestedScopes,
        )
    }

    fun issueAuthorizationCode(
        request: McpAuthorizationRequest,
        user: AuthenticatedUser,
    ): String {
        pruneExpiredGrants()
        val code = randomToken(32)
        grants[code] =
            AuthorizationCodeGrant(
                request = request,
                user = user,
                expiresAt = Instant.now().plus(codeLifetime),
            )
        return code
    }

    fun exchangeAuthorizationCode(
        code: String?,
        redirectUri: String?,
        codeVerifier: String?,
        authorizationHeader: String?,
        clientIdParam: String?,
        clientSecretParam: String?,
    ): McpTokenResult {
        pruneExpiredGrants()
        val grantCode = code?.takeIf { it.isNotBlank() } ?: throw OAuthTokenException("invalid_request", "Missing code")
        val stored = grants.remove(grantCode) ?: throw OAuthTokenException("invalid_grant", "Authorization code is invalid or expired")
        val resolvedRedirectUri =
            redirectUri?.takeIf { it.isNotBlank() } ?: throw OAuthTokenException("invalid_request", "Missing redirect_uri")

        if (stored.request.redirectUri != resolvedRedirectUri) {
            throw OAuthTokenException("invalid_grant", "redirect_uri does not match authorization code")
        }

        val client = authenticateClient(authorizationHeader, clientIdParam, clientSecretParam)
        if (client.clientId != stored.request.clientId) {
            throw OAuthTokenException("invalid_grant", "Authorization code was not issued to this client")
        }

        val verifier = codeVerifier?.takeIf { it.isNotBlank() } ?: throw OAuthTokenException("invalid_request", "Missing code_verifier")
        if (verifier.sha256UrlSafe() != stored.request.codeChallenge) {
            throw OAuthTokenException("invalid_grant", "code_verifier does not match code_challenge")
        }

        val now = Instant.now()
        val token =
            jwtEncoder.encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).build(),
                    JwtClaimsSet
                        .builder()
                        .issuer(properties.publicBaseUrl.trimEnd('/'))
                        .subject(stored.user.subject)
                        .audience(listOf("${properties.publicBaseUrl.trimEnd('/')}/mcp"))
                        .issuedAt(now)
                        .expiresAt(now.plus(tokenLifetime))
                        .claim("client_id", client.clientId)
                        .claim("email", stored.user.email)
                        .claim("name", stored.user.displayName)
                        .build(),
                ),
            )

        return McpTokenResult(token.tokenValue, tokenLifetime.seconds)
    }

    fun authorizationSuccessRedirect(
        request: McpAuthorizationRequest,
        code: String,
    ): String =
        buildString {
            append(request.redirectUri)
            append(if ('?' in request.redirectUri) '&' else '?')
            append("code=")
            append(code.urlEncode())
            append("&state=")
            append(request.state.urlEncode())
        }

    fun authorizationErrorRedirect(
        request: McpAuthorizationRequest,
        error: String,
    ): String =
        buildString {
            append(request.redirectUri)
            append(if ('?' in request.redirectUri) '&' else '?')
            append("error=")
            append(error.urlEncode())
            append("&state=")
            append(request.state.urlEncode())
        }

    fun tokenErrorResponse(exception: OAuthTokenException): ResponseEntity<Map<String, String>> {
        val headers = HttpHeaders()
        if (exception.status == HttpStatus.UNAUTHORIZED) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"htmlshare\"")
        }

        return ResponseEntity.status(exception.status).headers(headers).body(
            buildMap {
                put("error", exception.error)
                put("error_description", exception.errorDescription)
            },
        )
    }

    private fun authenticateClient(
        authorizationHeader: String?,
        clientIdParam: String?,
        clientSecretParam: String?,
    ): RegisteredClient {
        val basic =
            authorizationHeader
                ?.takeIf { it.startsWith("Basic ", ignoreCase = true) }
                ?.removePrefix("Basic ")
                ?.let { encoded ->
                    val decoded = String(Base64.getDecoder().decode(encoded), UTF_8)
                    val separator = decoded.indexOf(':')
                    if (separator <
                        0
                    ) {
                        throw OAuthTokenException("invalid_client", "Malformed basic authentication", HttpStatus.UNAUTHORIZED)
                    }
                    decoded.substring(0, separator) to decoded.substring(separator + 1)
                }

        val clientId =
            basic?.first ?: clientIdParam?.takeIf { it.isNotBlank() }
                ?: throw OAuthTokenException("invalid_client", "Missing client authentication", HttpStatus.UNAUTHORIZED)
        val clientSecret =
            basic?.second ?: clientSecretParam?.takeIf { it.isNotBlank() }
                ?: throw OAuthTokenException("invalid_client", "Missing client secret", HttpStatus.UNAUTHORIZED)

        val client =
            clients.findByClientId(clientId)
                ?: throw OAuthTokenException("invalid_client", "Unknown client", HttpStatus.UNAUTHORIZED)
        val storedSecret =
            client.clientSecret
                ?: throw OAuthTokenException("invalid_client", "Client secret is not configured", HttpStatus.UNAUTHORIZED)

        if (!passwordEncoder.matches(clientSecret, storedSecret)) {
            throw OAuthTokenException("invalid_client", "Client authentication failed", HttpStatus.UNAUTHORIZED)
        }

        return client
    }

    private fun pruneExpiredGrants() {
        val now = Instant.now()
        grants.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }

    private fun randomToken(size: Int): String {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun String.sha256UrlSafe(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(toByteArray(UTF_8)),
        )

    private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
}
