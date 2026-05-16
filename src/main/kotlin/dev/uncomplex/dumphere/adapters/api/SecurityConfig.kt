package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.adapters.mcp.OAuthMetadataEntryPoint
import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import dev.uncomplex.dumphere.application.UserProvisioningService
import dev.uncomplex.dumphere.application.authenticatedUser
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.FactorGrantedAuthority
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import java.time.Instant
import kotlin.collections.plus

@Configuration
class SecurityConfig(
    private val properties: DumpHereApplicationProperties,
    private val clientRegistrationRepository: ClientRegistrationRepository,
    private val userProvisioning: UserProvisioningService,
) {
    @Bean
    @Order(2)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf ->
                csrf.ignoringRequestMatchers(matcher("/mcp"), matcher("/api/**"), matcher("/connect/register"), matcher("/oauth2/token"))
            }.requestCache { cache -> cache.requestCache(requestCache()) }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers("/error", "/login/**", "/oauth2/authorization/**", "/login/oauth2/**")
                    .permitAll()
                    .requestMatchers(
                        "/.well-known/oauth-protected-resource",
                        "/.well-known/oauth-authorization-server",
                        "/.well-known/openid-configuration",
                        "/oauth2/jwks",
                        "/oauth2/token",
                        "/connect/register",
                    ).permitAll()
                    .anyRequest()
                    .access { authentication, _ ->
                        val auth = authentication.get()
                        AuthorizationDecision(
                            auth.isAuthenticated && auth !is AnonymousAuthenticationToken &&
                                emailAllowed(
                                    auth,
                                ),
                        )
                    }
            }.oauth2Login { oauth ->
                oauth.loginPage("/login")
                oauth.authorizationEndpoint { endpoint ->
                    endpoint.authorizationRequestResolver(googleAccountPickerRequestResolver(clientRegistrationRepository))
                }
                oauth.userInfoEndpoint { userInfo -> userInfo.userAuthoritiesMapper(authTimeAuthoritiesMapper()) }
                oauth.successHandler(authenticationSuccessHandler())
            }.oauth2ResourceServer { resourceServer ->
                resourceServer.authenticationEntryPoint(OAuthMetadataEntryPoint(properties))
                resourceServer.jwt { }
                resourceServer.protectedResourceMetadata { metadata ->
                    val baseUrl = properties.publicBaseUrl.trimEnd('/')
                    metadata.protectedResourceMetadataCustomizer { builder ->
                        builder
                            .resource("$baseUrl/mcp")
                            .authorizationServer(baseUrl)
                            .scope("openid")
                            .scope("email")
                            .scope("profile")
                            .bearerMethod("header")
                            .tlsClientCertificateBoundAccessTokens(false)
                    }
                }
            }.exceptionHandling { exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                    OAuthMetadataEntryPoint(properties),
                    matcher("/mcp"),
                )
                exceptions.defaultAuthenticationEntryPointFor(
                    HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    matcher("/api/**"),
                )
                exceptions.defaultAuthenticationEntryPointFor(loginEntryPoint(), matcher("/oauth2/authorize"))
                exceptions.defaultAuthenticationEntryPointFor(loginEntryPoint(), matcher("/oauth2/consent"))
                exceptions.defaultAuthenticationEntryPointFor(loginEntryPoint(), matcher("/p/**"))
            }

        return http.build()
    }

    private fun authenticationSuccessHandler(): AuthenticationSuccessHandler {
        val requestCache = requestCache()

        return AuthenticationSuccessHandler { request, response, authentication ->
            if (!emailAllowed(authentication)) {
                response.sendError(HttpStatus.FORBIDDEN.value(), "email domain not allowed")
                return@AuthenticationSuccessHandler
            }

            authentication.authenticatedUser()?.let { userProvisioning.provision(it) }

            response.sendRedirect("/login")
        }
    }

    @Bean
    fun requestCache(): HttpSessionRequestCache = HttpSessionRequestCache()

    private fun loginEntryPoint() = LoginUrlAuthenticationEntryPoint("/login")

    @Bean
    fun googleAccountPickerRequestResolver(
        clientRegistrationRepository: ClientRegistrationRepository,
    ): OAuth2AuthorizationRequestResolver {
        val resolver = DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/oauth2/authorization")
        resolver.setAuthorizationRequestCustomizer { builder ->
            builder.additionalParameters { params -> params["prompt"] = "select_account" }
        }
        return resolver
    }

    @Bean
    fun authTimeAuthoritiesMapper(): GrantedAuthoritiesMapper =
        GrantedAuthoritiesMapper { authorities ->
            authorities.withAuthenticationTime()
        }

    private fun Collection<GrantedAuthority>.withAuthenticationTime(): Collection<GrantedAuthority> =
        if (any { it is FactorGrantedAuthority }) {
            this
        } else {
            this +
                FactorGrantedAuthority
                    .withAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
                    .issuedAt(Instant.now())
                    .build()
        }

    private fun matcher(pattern: String) = PathPatternRequestMatcher.withDefaults().matcher(pattern)

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    private fun emailAllowed(authentication: Authentication): Boolean {
        val domain = properties.allowedEmailDomain?.trim().orEmpty()
        if (domain.isEmpty()) return true

        val email =
            when (val principal = authentication.principal) {
                is OidcUser -> principal.email
                is OAuth2AuthenticatedPrincipal -> principal.getAttribute("email")
                else -> null
            } ?: return false

        return email.endsWith("@$domain", ignoreCase = true)
    }
}
