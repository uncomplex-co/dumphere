package dev.uncomplex.dumphere.adapters.authentication

import dev.uncomplex.dumphere.application.AllowedEmailDomainPolicy
import dev.uncomplex.dumphere.application.UserEntity
import dev.uncomplex.dumphere.application.UserProvisioningService
import dev.uncomplex.dumphere.application.authenticatedUser
import dev.uncomplex.dumphere.ports.UserRepository
import org.springframework.context.event.EventListener
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

@Component
class AuthenticatedUsers(
    private val users: UserRepository,
    private val provisioning: UserProvisioningService,
    private val domains: AllowedEmailDomainPolicy,
) {
    @EventListener
    fun authenticated(event: AuthenticationSuccessEvent) {
        val authentication = event.authentication
        if (authentication !is UsernamePasswordAuthenticationToken && authentication !is OAuth2AuthenticationToken &&
            authentication !is OAuth2LoginAuthenticationToken && authentication !is JwtAuthenticationToken) return
        val identity = authentication.authenticatedUser() ?: return
        // Configured Basic API credentials are not subject to the browser/MCP email-domain restriction.
        if (authentication is UsernamePasswordAuthenticationToken || domains.isAllowed(identity)) {
            provisioning.provision(identity)
        }
    }

    fun resolve(authentication: Authentication?): UserEntity? {
        if (authentication == null || !authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) return null
        val identity = authentication.authenticatedUser() ?: return null
        return users.findBySubject(identity.subject)
            ?: throw BadCredentialsException("Authenticated user has not been provisioned")
    }
}
