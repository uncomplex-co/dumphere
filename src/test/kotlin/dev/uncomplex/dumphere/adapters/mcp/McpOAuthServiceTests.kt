package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import dev.uncomplex.dumphere.application.UserProvisioningService
import dev.uncomplex.dumphere.ports.UserRepository
import dev.uncomplex.dumphere.testing.PostgresTestDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

@SpringBootTest(
    properties = [
        "spring.security.oauth2.client.registration.google.client-id=test-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-secret",
    ],
)
class McpOAuthServiceTests {
    @Autowired private lateinit var service: McpOAuthService

    @Autowired private lateinit var clients: RegisteredClientRepository

    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    @Autowired private lateinit var decoder: JwtDecoder

    @Autowired private lateinit var encoder: JwtEncoder

    @Autowired private lateinit var properties: DumpHereApplicationProperties

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var provisioning: UserProvisioningService

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = PostgresTestDatabase.configure(registry)
    }

    @Test
    fun omitsMissingProfileClaimsFromAccessAndRefreshedTokens() {
        verifyTokenClaims(AuthenticatedUser(subject = UUID.randomUUID().toString()))
    }

    @Test
    fun preservesProfileClaimsInAccessAndRefreshedTokens() {
        verifyTokenClaims(AuthenticatedUser(subject = UUID.randomUUID().toString(), email = "user@example.com", displayName = "Test User"))
    }

    private fun verifyTokenClaims(user: AuthenticatedUser) {
        val (client, access) = issue(user)
        val refreshed = service.exchangeRefreshToken(access.refreshToken, null, client.clientId, "test-secret")

        assertNotEquals(access.refreshToken, refreshed.refreshToken)
        for (result in listOf(access, refreshed)) {
            val jwt = decoder.decode(result.accessToken)
            assertEquals(user.subject, jwt.subject)
            assertEquals(client.clientId, jwt.getClaimAsString("client_id"))
            assertEquals(user.email, jwt.getClaimAsString("email"))
            assertEquals(user.displayName, jwt.getClaimAsString("name"))
            assertEquals(3600L, result.expiresIn)
            assertEquals(Duration.ofHours(1), Duration.between(jwt.issuedAt, jwt.expiresAt))
            if (user.email == null) assertFalse(jwt.hasClaim("email"))
            if (user.displayName == null) assertFalse(jwt.hasClaim("name"))
        }
    }

    @Test
    fun persistsOnlyHashesWithThirtyDayExpiryAndRotatesAcrossServiceInstances() {
        val before = Instant.now()
        val (client, access) = issue(AuthenticatedUser("persistent-user", "user@example.com", "Persistent User"))
        val hashes = jdbc.queryForList("select token_hash from mcp_refresh_tokens where client_id = ?", String::class.java, client.clientId)
        assertEquals(listOf(hash(access.refreshToken)), hashes)
        assertFalse(hashes.contains(access.refreshToken))
        val expiry =
            jdbc
                .queryForObject(
                    "select expires_at from mcp_refresh_tokens where client_id = ?",
                    Timestamp::class.java,
                    client.clientId,
                )!!
                .toInstant()
        assertTrue(!expiry.isBefore(before.plus(Duration.ofDays(30))))
        assertTrue(!expiry.isAfter(Instant.now().plus(Duration.ofDays(30))))

        // A new service and repository have no access to the original service's memory.
        val restarted = McpOAuthService(clients, passwordEncoder, encoder, properties, JdbcMcpRefreshTokens(jdbc), users)
        val refreshed = restarted.exchangeRefreshToken(access.refreshToken, null, client.clientId, "test-secret")
        assertEquals("persistent-user", decoder.decode(refreshed.accessToken).subject)
        assertEquals("user@example.com", decoder.decode(refreshed.accessToken).getClaimAsString("email"))
        assertEquals(
            listOf(hash(refreshed.refreshToken)),
            jdbc.queryForList(
                "select token_hash from mcp_refresh_tokens where client_id = ?",
                String::class.java,
                client.clientId,
            ),
        )
        assertFailsWith<OAuthTokenException> { service.exchangeRefreshToken(access.refreshToken, null, client.clientId, "test-secret") }
        service.exchangeRefreshToken(refreshed.refreshToken, null, client.clientId, "test-secret")
    }

    @Test
    fun rejectsExpiredTokensAndBadClientCredentialsWithoutConsumingValidTokens() {
        val (client, access) = issue(AuthenticatedUser("expiry-user"))
        assertFailsWith<OAuthTokenException> { service.exchangeRefreshToken(access.refreshToken, null, client.clientId, "wrong-secret") }
        val (other, _) = issue(AuthenticatedUser("other-user"))
        assertFailsWith<OAuthTokenException> { service.exchangeRefreshToken(access.refreshToken, null, other.clientId, "test-secret") }
        val rotated = service.exchangeRefreshToken(access.refreshToken, null, client.clientId, "test-secret")
        jdbc.update(
            "update mcp_refresh_tokens set expires_at = ? where token_hash = ?",
            Timestamp.from(Instant.now().minusSeconds(1)),
            hash(rotated.refreshToken),
        )
        val error =
            assertFailsWith<OAuthTokenException> {
                service.exchangeRefreshToken(
                    rotated.refreshToken,
                    null,
                    client.clientId,
                    "test-secret",
                )
            }
        assertEquals("invalid_grant", error.error)
    }

    @Test
    fun onlyOneConcurrentRefreshCanConsumeAToken() {
        val (client, access) = issue(AuthenticatedUser("concurrent-user"))
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val attempts =
                (1..2).map {
                    executor.submit<Boolean> {
                        start.await()
                        try {
                            service.exchangeRefreshToken(access.refreshToken, null, client.clientId, "test-secret")
                            true
                        } catch (error: OAuthTokenException) {
                            assertEquals("invalid_grant", error.error)
                            false
                        }
                    }
                }
            start.countDown()
            assertEquals(1, attempts.count { it.get(10, TimeUnit.SECONDS) })
        }
        assertEquals(
            1,
            jdbc.queryForObject("select count(*) from mcp_refresh_tokens where client_id = ?", Int::class.java, client.clientId),
        )
    }

    @Test
    fun refusesToIssueTokensForAnIdentityThatIsNotASystemUser() {
        val subject = UUID.randomUUID().toString()
        val before = jdbc.queryForObject("select count(*) from mcp_refresh_tokens where expires_at > current_timestamp", Int::class.java)
        val error = assertFailsWith<OAuthTokenException> { issue(AuthenticatedUser(subject), provisionUser = false) }
        assertEquals("invalid_grant", error.error)
        assertNull(users.findBySubject(subject))
        assertEquals(before, jdbc.queryForObject("select count(*) from mcp_refresh_tokens where expires_at > current_timestamp", Int::class.java))
    }

    @Test
    fun deletingAUserCascadesToTheirRefreshTokensAndPreventsRefresh() {
        val subject = UUID.randomUUID().toString()
        val (client, access) = issue(AuthenticatedUser(subject))
        val userId = requireNotNull(users.findBySubject(subject)?.id)
        assertEquals(userId, jdbc.queryForObject("select user_id from mcp_refresh_tokens where token_hash = ?", Long::class.java, hash(access.refreshToken)))
        users.deleteById(userId)
        assertEquals(0, jdbc.queryForObject("select count(*) from mcp_refresh_tokens where user_id = ?", Int::class.java, userId))
        val error = assertFailsWith<OAuthTokenException> { service.exchangeRefreshToken(access.refreshToken, null, client.clientId, "test-secret") }
        assertEquals("invalid_grant", error.error)
    }

    @Test
    fun refreshedClaimsComeFromTheCurrentUserRowNotTheOriginalLoginSnapshot() {
        val subject = UUID.randomUUID().toString()
        val (client, access) = issue(AuthenticatedUser(subject, "old@example.com", "Old Name"))
        jdbc.update("update users set email = ?, display_name = ? where subject = ?", "new@example.com", "New Name", subject)
        val refreshed = service.exchangeRefreshToken(access.refreshToken, null, client.clientId, "test-secret")
        val jwt = decoder.decode(refreshed.accessToken)
        assertEquals("new@example.com", jwt.getClaimAsString("email"))
        assertEquals("New Name", jwt.getClaimAsString("name"))
        assertEquals(requireNotNull(users.findBySubject(subject)?.id), jdbc.queryForObject(
            "select user_id from mcp_refresh_tokens where token_hash = ?", Long::class.java, hash(refreshed.refreshToken),
        ))
    }

    private fun issue(user: AuthenticatedUser, provisionUser: Boolean = true): Pair<RegisteredClient, McpTokenResult> {
        if (provisionUser) provisioning.provision(user)
        val id = UUID.randomUUID().toString()
        val client =
            RegisteredClient
                .withId(id)
                .clientId(id)
                .clientSecret(requireNotNull(passwordEncoder.encode("test-secret")))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost/callback")
                .scope("openid")
                .build()
        clients.save(client)
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
        return client to access
    }

    private fun hash(token: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}
