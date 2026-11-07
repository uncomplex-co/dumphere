package dev.uncomplex.dumphere.application

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.jwt.Jwt

class AllowedEmailDomainPolicyTests {
    @Test
    fun allowsAnyEmailWhenNoDomainIsConfigured() {
        // Arrange
        val policy = policy("")

        // Act & Assert
        assertTrue(policy.isAllowed(authentication(jwtPrincipal(email = "user@anywhere.test"))))
        assertTrue(policy.isAllowed(null as AuthenticatedUser?))
    }

    @Test
    fun allowsMatchingOidcEmail() {
        // Arrange
        val policy = policy("example.com")

        // Act & Assert
        assertTrue(policy.isAllowed(authentication(oidcPrincipal(email = "user@example.com"))))
    }

    @Test
    fun allowsMatchingJwtEmail() {
        // Arrange
        val policy = policy("example.com")

        // Act & Assert
        assertTrue(policy.isAllowed(authentication(jwtPrincipal(email = "user@example.com"))))
    }

    @Test
    fun rejectsNonMatchingOrMissingEmail() {
        // Arrange
        val policy = policy("example.com")

        // Act & Assert
        assertFalse(policy.isAllowed(authentication(jwtPrincipal(email = "user@blocked.test"))))
        assertFalse(policy.isAllowed(AuthenticatedUser(subject = "user-123")))
    }

    private fun policy(domain: String) =
        AllowedEmailDomainPolicy(
            SamePageAppProperties(
                publicBaseUrl = "http://localhost:7331",
                maxHtmlBytes = 1024,
                allowedEmailDomain = domain,
                apiUsername = "htmlshare",
                apiPassword = "htmlshare", // betterleaks:allow -- Dummy test credential.
            )
        )

    private fun authentication(principal: Any) =
        TestingAuthenticationToken(principal, "credentials").apply {
            isAuthenticated = true
        }

    private fun jwtPrincipal(email: String?) =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", "user-123")
            .apply {
                if (email != null) claim("email", email)
            }
            .build()

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
