package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.adapters.mcp.OAuthMetadataEntryPoint
import dev.uncomplex.dumphere.application.AllowedEmailDomainPolicy
import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import dev.uncomplex.dumphere.application.UserProvisioningService
import dev.uncomplex.dumphere.application.authenticatedUser
import jakarta.servlet.http.HttpServletRequest
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
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import java.time.Instant
import kotlin.collections.plus

@Configuration
class SecurityConfig(
    private val properties: DumpHereApplicationProperties,
    private val clientRegistrationRepository: ClientRegistrationRepository,
    private val allowedEmailDomainPolicy: AllowedEmailDomainPolicy,
    private val userProvisioning: UserProvisioningService,
) {
    @Bean
    @Order(1)
    fun apiSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/**")
            .csrf { it.disable() }
            .authorizeHttpRequests { requests -> requests.anyRequest().authenticated() }
            .httpBasic { }

        return http.build()
    }

    @Bean
    @Order(2)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf ->
                csrf.ignoringRequestMatchers(matcher("/mcp"), matcher("/api/**"), matcher("/connect/register"), matcher("/oauth2/token"))
            }.authorizeHttpRequests { requests ->
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
                                allowedEmailDomainPolicy.isAllowed(auth),
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
            }.headers { headers ->
                headers.frameOptions { it.sameOrigin() }
            }.exceptionHandling { exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                    OAuthMetadataEntryPoint(properties),
                    matcher("/mcp"),
                )
                exceptions.defaultAuthenticationEntryPointFor(
                    HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    matcher("/api/**"),
                )
                exceptions.defaultAuthenticationEntryPointFor(loginRedirectEntryPoint(), matcher("/oauth2/authorize"))
                exceptions.defaultAuthenticationEntryPointFor(loginRedirectEntryPoint(), matcher("/oauth2/consent"))
                exceptions.defaultAuthenticationEntryPointFor(loginRedirectEntryPoint(), matcher("/p/**"))
            }

        return http.build()
    }

    private fun authenticationSuccessHandler(): AuthenticationSuccessHandler {
        return AuthenticationSuccessHandler { request, response, authentication ->
            if (!allowedEmailDomainPolicy.isAllowed(authentication)) {
                request.getSession(false)?.invalidate()
                SecurityContextHolder.clearContext()
                response.sendError(HttpStatus.FORBIDDEN.value(), "email domain not allowed")
                return@AuthenticationSuccessHandler
            }

            authentication.authenticatedUser()?.let { userProvisioning.provision(it) }

            response.sendRedirect(RedirectUrlSupport.take(request.getSession(false), request.getParameter("state")) ?: "/login/success")
        }
    }

    private fun loginRedirectEntryPoint(): AuthenticationEntryPoint =
        AuthenticationEntryPoint { request, response, _ ->
            response.sendRedirect(RedirectUrlSupport.loginUrl(RedirectUrlSupport.currentRequestUrl(request)))
        }

    @Bean
    fun googleAccountPickerRequestResolver(
        clientRegistrationRepository: ClientRegistrationRepository,
    ): OAuth2AuthorizationRequestResolver {
        val delegate = DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/oauth2/authorization")
        delegate.setAuthorizationRequestCustomizer { builder ->
            builder.additionalParameters { params -> params["prompt"] = "select_account" }
        }

        return object : OAuth2AuthorizationRequestResolver {
            override fun resolve(request: HttpServletRequest): OAuth2AuthorizationRequest? =
                customizeAuthorizationRequest(delegate.resolve(request), request)

            override fun resolve(request: HttpServletRequest, clientRegistrationId: String): OAuth2AuthorizationRequest? =
                customizeAuthorizationRequest(delegate.resolve(request, clientRegistrationId), request)
        }
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

    private fun customizeAuthorizationRequest(
        authorizationRequest: OAuth2AuthorizationRequest?,
        request: HttpServletRequest,
    ): OAuth2AuthorizationRequest? {
        val resolvedRequest = authorizationRequest ?: return null

        val redirectUrl = RedirectUrlSupport.sanitize(request.getParameter("redirectUrl"))
        if (redirectUrl == null) return resolvedRequest

        val stateId = RedirectUrlSupport.newStateId()
        RedirectUrlSupport.remember(request.session, stateId, redirectUrl)

        return OAuth2AuthorizationRequest.from(resolvedRequest)
            .state(stateId)
            .build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun userDetailsService(passwordEncoder: PasswordEncoder): UserDetailsService =
        InMemoryUserDetailsManager(
            User.withUsername(properties.apiUsername)
                .password(passwordEncoder.encode(properties.apiPassword))
                .roles("API")
                .build(),
        )
}
