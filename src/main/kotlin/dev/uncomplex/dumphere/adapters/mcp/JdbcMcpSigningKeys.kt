package dev.uncomplex.dumphere.adapters.mcp

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID

@Repository
class JdbcMcpSigningKeys(
    private val jdbc: JdbcTemplate,
) {
    fun loadOrCreate(): RSAKey {
        read()?.let { return it }

        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val candidate =
            RSAKey
                .Builder(pair.public as RSAPublicKey)
                .privateKey(pair.private as RSAPrivateKey)
                .keyID(UUID.randomUUID().toString())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build()
        jdbc.update(
            "insert into mcp_signing_keys (key_name, private_jwk) values ('active', ?) on conflict (key_name) do nothing",
            candidate.toJSONString(),
        )
        // Concurrent first startups must all use the persisted winner, not their generated candidate.
        return requireNotNull(read()) { "MCP signing key was not persisted" }
    }

    private fun read(): RSAKey? =
        jdbc
            .query(
                "select private_jwk from mcp_signing_keys where key_name = 'active'",
                { row, _ -> RSAKey.parse(row.getString("private_jwk")) },
            ).singleOrNull()
            ?.also { require(it.isPrivate) { "Stored MCP signing key has no private key" } }
}
