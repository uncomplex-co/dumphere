package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.adapters.api.AuthorizationServerConfig
import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class McpOAuthServiceTests {
    @Test
    fun omitsMissingProfileClaimsFromAccessAndRefreshedTokens() {
        verifyTokenClaims(AuthenticatedUser(subject = "user"))
    }

    @Test
    fun preservesProfileClaimsInAccessAndRefreshedTokens() {
        verifyTokenClaims(AuthenticatedUser(subject = "user", email = "user@example.com", displayName = "Test User"))
    }

    private fun verifyTokenClaims(user: AuthenticatedUser) {
        val passwordEncoder = BCryptPasswordEncoder()
        val client =
            RegisteredClient
                .withId("test-registration")
                .clientId("test-client")
                .clientSecret(requireNotNull(passwordEncoder.encode("test-secret")))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost/callback")
                .scope("openid")
                .build()
        val config = AuthorizationServerConfig()
        val keys = config.jwkSet()
        val decoder = config.jwtDecoder(keys)
        val service =
            McpOAuthService(
                clients = InMemoryRegisteredClientRepository(client),
                passwordEncoder = passwordEncoder,
                jwtEncoder = config.jwtEncoder(config.jwkSource(keys)),
                properties =
                    DumpHereApplicationProperties(
                        storageDir = "/unused",
                        publicBaseUrl = "http://localhost:7331",
                        maxHtmlBytes = 1024,
                        apiUsername = "htmlshare",
                        apiPassword = "htmlshare",
                    ),
            )
        val verifier = "test-code-verifier-with-at-least-forty-three-characters"
        val challenge =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val request =
            service.validateAuthorizationRequest(
                responseType = "code",
                clientId = client.clientId,
                redirectUri = "http://localhost/callback",
                state = "test-state",
                codeChallenge = challenge,
                codeChallengeMethod = "S256",
                scope = "openid",
            )
        val code = service.issueAuthorizationCode(request, user)

        val access =
            service.exchangeAuthorizationCode(
                code = code,
                redirectUri = request.redirectUri,
                codeVerifier = verifier,
                authorizationHeader = null,
                clientIdParam = client.clientId,
                clientSecretParam = "test-secret",
            )
        val refreshed = service.exchangeRefreshToken(access.refreshToken, null, client.clientId, "test-secret")

        assertNotEquals(access.refreshToken, refreshed.refreshToken)
        for (result in listOf(access, refreshed)) {
            val jwt = decoder.decode(result.accessToken)
            assertEquals(user.subject, jwt.subject)
            assertEquals(client.clientId, jwt.getClaimAsString("client_id"))
            assertEquals(user.email, jwt.getClaimAsString("email"))
            assertEquals(user.displayName, jwt.getClaimAsString("name"))
            if (user.email == null) assertFalse(jwt.hasClaim("email"))
            if (user.displayName == null) assertFalse(jwt.hasClaim("name"))
        }
    }
}
