package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.testing.PostgresTestDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoderFactory
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.CookieManager
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.security.oauth2.client.registration.google.client-id=test-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-secret",
        "htmlshare.allowed-email-domain=example.com",
    ],
)
@Import(McpLoginIntegrationTests.GoogleBoundary::class)
class McpLoginIntegrationTests {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var clients: RegisteredClientRepository

    @Autowired private lateinit var passwords: PasswordEncoder

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var json: ObjectMapper

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = PostgresTestDatabase.configure(registry)
    }

    @Test
    fun loggedOutMcpAuthorizationResumesAtConsentAfterGoogleCallback() {
        val clientId = UUID.randomUUID().toString()
        clients.save(
            RegisteredClient
                .withId(clientId)
                .clientId(clientId)
                .clientName("Regression MCP client")
                .clientSecret(requireNotNull(passwords.encode("test-secret")))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://127.0.0.1:9999/callback")
                .scope("openid")
                .scope("email")
                .scope("profile")
                .clientSettings(
                    ClientSettings
                        .builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .build(),
                ).build(),
        )
        val browser = HttpClient.newBuilder().cookieHandler(CookieManager()).build()

        fun get(path: String): HttpResponse<String> =
            browser.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port").resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        fun location(response: HttpResponse<String>): String {
            assertEquals(302, response.statusCode(), response.body())
            return response.headers().firstValue("location").orElseThrow()
        }

        val verifier = "test-verifier-" + "a".repeat(43)
        val challenge =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val target =
            "/oauth2/authorize?response_type=code&client_id=$clientId" +
                "&redirect_uri=http%3A%2F%2F127.0.0.1%3A9999%2Fcallback&state=mcp-state" +
                "&code_challenge=$challenge&code_challenge_method=S256&scope=openid+email+profile"
        val login = get(location(get(target)))
        assertEquals(200, login.statusCode())
        val googleLink =
            Regex("href=\"([^\"]*oauth2/authorization/google[^\"]*)\"")
                .find(login.body())!!
                .groupValues[1]
                .replace("&amp;", "&")
        val googleRedirect = location(get(googleLink))
        get("/favicon.ico")
        val googleState =
            URI
                .create(googleRedirect)
                .rawQuery
                .split("&")
                .first { it.startsWith("state=") }
                .substringAfter("=")
                .let { URLDecoder.decode(it, Charsets.UTF_8) }
        val afterLogin = location(get("/login/oauth2/code/google?code=test-code&state=${URLEncoder.encode(googleState, Charsets.UTF_8)}"))
        assertEquals(target, URI.create(afterLogin).let { it.rawPath + "?" + it.rawQuery })
        val consentUrl = location(get(afterLogin))
        assertEquals("/oauth2/consent?state=mcp-state", URI.create(consentUrl).let { it.rawPath + "?" + it.rawQuery })
        val consent = get(consentUrl)
        assertEquals(200, consent.statusCode(), consent.body())
        assertContains(consent.body(), "Approve MCP access")
        assertContains(consent.body(), "Regression MCP client")
        assertEquals(0, jdbc.queryForObject("select count(*) from mcp_refresh_tokens where client_id = ?", Int::class.java, clientId))

        fun post(
            path: String,
            form: Map<String, String>,
        ): HttpResponse<String> =
            browser.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port").resolve(path))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            form.entries.joinToString("&") { (key, value) ->
                                "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
                            },
                        ),
                    ).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        val csrf = Regex("name=\"_csrf\" value=\"([^\"]+)\"").find(consent.body())!!.groupValues[1]
        val callback = location(post("/oauth2/consent", mapOf("state" to "mcp-state", "decision" to "approve", "_csrf" to csrf)))
        val code =
            URI
                .create(callback)
                .rawQuery
                .split("&")
                .first { it.startsWith("code=") }
                .substringAfter("=")
        val issued =
            post(
                "/oauth2/token",
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to URLDecoder.decode(code, Charsets.UTF_8),
                    "redirect_uri" to "http://127.0.0.1:9999/callback",
                    "code_verifier" to verifier,
                    "client_id" to clientId,
                    "client_secret" to "test-secret",
                ),
            )
        assertEquals(200, issued.statusCode(), issued.body())
        val issuedBody = json.readTree(issued.body())
        assertEquals(3600L, issuedBody.path("expires_in").asLong())
        val refreshToken = issuedBody.path("refresh_token").asString()
        val hash =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray()))
        assertEquals(
            listOf(hash),
            jdbc.queryForList("select token_hash from mcp_refresh_tokens where client_id = ?", String::class.java, clientId),
        )
        val refreshForm =
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
                "client_secret" to "test-secret",
            )
        val refreshed = post("/oauth2/token", refreshForm)
        assertEquals(200, refreshed.statusCode(), refreshed.body())
        assertEquals(3600L, json.readTree(refreshed.body()).path("expires_in").asLong())
        assertEquals(0, jdbc.queryForObject("select count(*) from mcp_refresh_tokens where token_hash = ?", Int::class.java, hash))
        assertEquals(1, jdbc.queryForObject("select count(*) from mcp_refresh_tokens where client_id = ?", Int::class.java, clientId))
        val replay = post("/oauth2/token", refreshForm)
        assertEquals(400, replay.statusCode())
        assertEquals("invalid_grant", json.readTree(replay.body()).path("error").asString())
    }

    // Replace only Google's external token and identity boundary; use the real login filters and JDBC sessions.
    @TestConfiguration(proxyBeanMethods = false)
    class GoogleBoundary {
        private val tokens = ConcurrentHashMap<String, Jwt>()

        @Bean
        fun googleTokens(): OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> =
            OAuth2AccessTokenResponseClient { grant ->
                val token = UUID.randomUUID().toString()
                val now = Instant.now()
                tokens[token] =
                    Jwt
                        .withTokenValue(token)
                        .header("alg", "RS256")
                        .subject("login-regression-user")
                        .issuer("https://accounts.google.com")
                        .audience(listOf("test-client"))
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("email", "login-regression@example.com")
                        .claim("name", "Login regression")
                        .claim("nonce", requireNotNull(grant.authorizationExchange.authorizationRequest.additionalParameters["nonce"]))
                        .build()
                OAuth2AccessTokenResponse
                    .withToken("test-access-token")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .expiresIn(300)
                    .additionalParameters(mapOf("id_token" to token))
                    .build()
            }

        @Bean
        fun googleJwtDecoder(): JwtDecoderFactory<ClientRegistration> =
            JwtDecoderFactory { JwtDecoder { token -> requireNotNull(tokens[token]) } }

        @Bean
        fun googleUserInfo(): OAuth2UserService<OidcUserRequest, OidcUser> =
            OAuth2UserService { request -> DefaultOidcUser(emptyList(), request.idToken) }
    }
}
