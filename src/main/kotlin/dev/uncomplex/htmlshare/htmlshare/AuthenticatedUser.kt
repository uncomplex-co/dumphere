package dev.uncomplex.htmlshare.htmlshare

import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.jwt.Jwt

data class AuthenticatedUser(
    val subject: String,
    val email: String? = null,
    val displayName: String? = null,
)

fun Authentication?.authenticatedUser(): AuthenticatedUser? {
    val authentication = this ?: return null
    val principal = authentication.principal
    return when (principal) {
        is Jwt -> AuthenticatedUser(
            subject = principal.subject ?: authentication.name,
            email = principal.getClaimAsString("email"),
            displayName = principal.getClaimAsString("name"),
        )
        is OidcUser -> AuthenticatedUser(
            subject = principal.subject,
            email = principal.email,
            displayName = principal.fullName,
        )
        is OAuth2AuthenticatedPrincipal -> AuthenticatedUser(
            subject = principal.getAttribute<String>("sub") ?: authentication.name,
            email = principal.getAttribute("email"),
            displayName = principal.getAttribute("name"),
        )
        else -> AuthenticatedUser(subject = authentication.name)
    }.takeUnless { it.subject.isBlank() }
}
