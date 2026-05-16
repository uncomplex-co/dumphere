package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.AllowedEmailDomainPolicy
import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoginControllerTests {
    private val requestCache = HttpSessionRequestCache()
    private val controller = LoginController(allowedEmailDomainPolicy = policy(), requestCache = requestCache)

    @Test
    fun redirectsAuthenticatedUserToSavedRequestWithQueryStringIntact() {
        val originalRequest =
            MockHttpServletRequest("GET", "/oauth2/authorize").apply {
                queryString = "client_id=dumphere&redirect_uri=https%3A%2F%2Fexample.com%2Fcallback&response_type=code&state=abc"
                setParameter("client_id", "dumphere")
                setParameter("redirect_uri", "https://example.com/callback")
                setParameter("response_type", "code")
                setParameter("state", "abc")
                session
            }
        val originalResponse = MockHttpServletResponse()
        requestCache.saveRequest(originalRequest, originalResponse)

        val loginRequest =
            MockHttpServletRequest("GET", "/login").apply {
                setSession(requireNotNull(originalRequest.session))
            }
        val loginResponse = MockHttpServletResponse()

        controller.login(loginRequest, loginResponse, authenticatedUser())

        val redirectedUrl = assertNotNull(loginResponse.redirectedUrl)

        assertTrue(redirectedUrl.startsWith("http://localhost/oauth2/authorize?"))
        assertTrue(redirectedUrl.contains("client_id=dumphere"))
        assertTrue(redirectedUrl.contains("redirect_uri=https%3A%2F%2Fexample.com%2Fcallback"))
        assertTrue(redirectedUrl.contains("response_type=code"))
        assertTrue(redirectedUrl.contains("state=abc"))
    }

    @Test
    fun doesNotRedirectAuthenticatedUserWhenEmailDomainIsNotAllowed() {
        val loginRequest = MockHttpServletRequest("GET", "/login")
        val loginResponse = MockHttpServletResponse()

        val result = controller.login(loginRequest, loginResponse, authenticatedUser(email = "user@blocked.test"))

        assertTrue(loginResponse.redirectedUrl == null)
        assertNotNull(result)
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
            ),
        )
}
