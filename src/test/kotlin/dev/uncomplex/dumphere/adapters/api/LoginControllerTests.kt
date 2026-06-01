package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.AllowedEmailDomainPolicy
import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginControllerTests {
    private val controller = LoginController(allowedEmailDomainPolicy = policy())

    @Test
    fun redirectsAuthenticatedUserToRedirectUrlWithQueryStringIntact() {
        val loginRequest = MockHttpServletRequest("GET", "/login")
        val loginResponse = MockHttpServletResponse()

        controller.login(
            loginRequest,
            loginResponse,
            authenticatedUser(),
            "/oauth2/authorize?client_id=dumphere&redirect_uri=https%3A%2F%2Fexample.com%2Fcallback&response_type=code&state=abc",
        )

        val redirectedUrl = assertNotNull(loginResponse.redirectedUrl)

        assertTrue(redirectedUrl.startsWith("/oauth2/authorize?"))
        assertTrue(redirectedUrl.contains("client_id=dumphere"))
        assertTrue(redirectedUrl.contains("redirect_uri=https%3A%2F%2Fexample.com%2Fcallback"))
        assertTrue(redirectedUrl.contains("response_type=code"))
        assertTrue(redirectedUrl.contains("state=abc"))
    }

    @Test
    fun doesNotRedirectAuthenticatedUserWhenEmailDomainIsNotAllowed() {
        val loginRequest = MockHttpServletRequest("GET", "/login")
        val loginResponse = MockHttpServletResponse()

        val result = controller.login(loginRequest, loginResponse, authenticatedUser(email = "user@blocked.test"), "/oauth2/authorize")

        assertNull(loginResponse.redirectedUrl)
        assertNotNull(result)
    }

    @Test
    fun rendersGoogleLinkWithRedirectUrl() {
        val loginRequest = MockHttpServletRequest("GET", "/login")
        val loginResponse = MockHttpServletResponse()

        val body = controller.login(loginRequest, loginResponse, null, "/oauth2/authorize?client_id=dumphere")

        assertNotNull(body)
        assertTrue(body.contains("/oauth2/authorization/google?redirectUrl="))
        assertTrue(body.contains("oauth2"))
        assertTrue(body.contains("client_id"))
    }

    @Test
    fun ignoresUnsafeRedirectUrl() {
        val loginRequest = MockHttpServletRequest("GET", "/login")
        val loginResponse = MockHttpServletResponse()

        val body = controller.login(loginRequest, loginResponse, null, "https://evil.example/phish")

        assertNotNull(body)
        assertTrue(body.contains("href=\"/oauth2/authorization/google\""))
    }

    private fun authenticatedUser(email: String = "user@example.com") =
        TestingAuthenticationToken(jwt(email), "credentials").apply {
            isAuthenticated = true
        }

    private fun jwt(email: String) =
        Jwt
            .withTokenValue("token")
            .header("alg", "none")
            .claim("sub", "user")
            .claim("email", email)
            .build()

    private fun policy(domain: String = "example.com") =
        AllowedEmailDomainPolicy(
            DumpHereApplicationProperties(
                storageDir = "/tmp/htmlshare-test",
                publicBaseUrl = "http://localhost:7331",
                maxHtmlBytes = 1024,
                allowedEmailDomain = domain,
                apiUsername = "htmlshare",
                apiPassword = "htmlshare",
            ),
        )
}
