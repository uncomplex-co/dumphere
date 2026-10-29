package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.AllowedEmailDomainPolicy
import dev.uncomplex.dumphere.application.ArtifactVisibility
import dev.uncomplex.dumphere.application.PublishedPage
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

@Component
class ArtifactAccess(
    private val domains: AllowedEmailDomainPolicy,
) {
    fun isMember(authentication: Authentication?): Boolean =
        authentication != null && authentication.isAuthenticated &&
            authentication !is AnonymousAuthenticationToken && domains.isAllowed(authentication)

    fun canView(
        page: PublishedPage,
        authentication: Authentication?,
    ): Boolean = isMember(authentication) || page.visibility.visibility == ArtifactVisibility.PUBLIC
}
