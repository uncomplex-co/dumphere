package dev.uncomplex.dumphere.application

import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service

@Service
class AllowedEmailDomainPolicy(
    private val properties: DumpHereApplicationProperties,
) {
    fun isAllowed(authentication: Authentication?): Boolean = isAllowed(authentication.authenticatedUser())

    fun isAllowed(user: AuthenticatedUser?): Boolean {
        val domain =
            properties.allowedEmailDomain
                ?.trim()
                .orEmpty()
                .removePrefix("@")
        if (domain.isEmpty()) return true

        val emailDomain = user?.email?.trim()?.substringAfterLast('@', missingDelimiterValue = "") ?: return false
        return emailDomain.equals(domain, ignoreCase = true)
    }
}
