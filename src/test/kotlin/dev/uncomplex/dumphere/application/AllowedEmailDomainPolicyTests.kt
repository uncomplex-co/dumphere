package dev.uncomplex.dumphere.application

import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.jwt.Jwt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllowedEmailDomainPolicyTests {
    @Test
    fun allowsAnyEmailWhenNoDomainIsConfigured() {
        val policy = policy("")

        assertTrue(policy.isAllowed(authentication(jwtPrincipal(email = "user@anywhere.test"))))
        assertTrue(policy.isAllowed(null as AuthenticatedUser?))
    }

    @Test
    fun allowsMatchingOidcEmail() {
        val policy = policy("example.com")

        assertTrue(policy.isAllowed(authentication(oidcPrincipal(email = "user@example.com"))))
    }

    @Test
    fun allowsMatchingJwtEmail() {
        val policy = policy("example.com")

        assertTrue(policy.isAllowed(authentication(jwtPrincipal(email = "user@example.com"))))
    }

    @Test
    fun rejectsNonMatchingOrMissingEmail() {
        val policy = policy("example.com")

        assertFalse(policy.isAllowed(authentication(jwtPrincipal(email = "user@blocked.test"))))
        assertFalse(policy.isAllowed(AuthenticatedUser(subject = "user-123")))
    }

    private fun policy(domain: String) =
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

    private fun authentication(principal: Any) =
        TestingAuthenticationToken(principal, "credentials").apply {
            isAuthenticated = true
        }

    private fun jwtPrincipal(email: String?) =
        Jwt
            .withTokenValue("token")
            .header("alg", "none")
            .claim("sub", "user-123")
            .apply {
                if (email != null) claim("email", email)
            }.build()

    private fun oidcPrincipal(email: String): DefaultOidcUser {
        val idToken =
            OidcIdToken(
                "token",
                null,
                null,
                mapOf(
                    "sub" to "user-123",
                    "email" to email,
                ),
            )

        return DefaultOidcUser(listOf(SimpleGrantedAuthority("ROLE_USER")), idToken)
    }
}
