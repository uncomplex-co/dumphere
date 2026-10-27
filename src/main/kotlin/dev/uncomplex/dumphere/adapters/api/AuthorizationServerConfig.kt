package dev.uncomplex.dumphere.adapters.api

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import dev.uncomplex.dumphere.adapters.mcp.JdbcMcpSigningKeys
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository

@Configuration
class AuthorizationServerConfig {
    @Bean
    fun registeredClientRepository(jdbcTemplate: JdbcTemplate): RegisteredClientRepository = JdbcRegisteredClientRepository(jdbcTemplate)

    @Bean
    @DependsOnDatabaseInitialization
    fun jwkSet(signingKeys: JdbcMcpSigningKeys): JWKSet = JWKSet(signingKeys.loadOrCreate())

    @Bean
    fun jwkSource(jwkSet: JWKSet): JWKSource<SecurityContext> = ImmutableJWKSet(jwkSet)

    @Bean
    fun jwtEncoder(jwkSource: JWKSource<SecurityContext>): JwtEncoder = NimbusJwtEncoder(jwkSource)

    @Bean
    fun jwtDecoder(jwkSet: JWKSet): JwtDecoder {
        val rsaKey = jwkSet.keys.first() as RSAKey
        return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build()
    }
}
