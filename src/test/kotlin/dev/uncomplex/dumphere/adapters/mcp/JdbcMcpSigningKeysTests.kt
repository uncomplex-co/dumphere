package dev.uncomplex.dumphere.adapters.mcp

import com.nimbusds.jose.jwk.JWKSet
import dev.uncomplex.dumphere.adapters.api.AuthorizationServerConfig
import org.junit.jupiter.api.AfterAll
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class JdbcMcpSigningKeysTests {
    companion object {
        private val database = PostgreSQLContainer("postgres:17-alpine").apply { start() }
        private val dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

        init {
            ResourceDatabasePopulator(ClassPathResource("db/migration/V19__persist_mcp_signing_keys.sql")).execute(dataSource)
        }

        @JvmStatic
        @AfterAll
        fun stopDatabase() = database.stop()
    }

    private val jdbc = JdbcTemplate(dataSource)

    @BeforeTest
    fun clearKeys() {
        jdbc.update("delete from mcp_signing_keys")
    }

    @Test
    fun newInstancesReuseThePersistedKeyAndValidatePreviouslyIssuedAccessTokens() {
        val original = JdbcMcpSigningKeys(JdbcTemplate(dataSource)).loadOrCreate()
        val config = AuthorizationServerConfig()
        val now = Instant.now()
        val token =
            config
                .jwtEncoder(config.jwkSource(JWKSet(original)))
                .encode(
                    JwtEncoderParameters.from(
                        JwtClaimsSet
                            .builder()
                            .subject("restart-user")
                            .issuedAt(now)
                            .expiresAt(now.plusSeconds(3600))
                            .build(),
                    ),
                ).tokenValue

        val reloaded = JdbcMcpSigningKeys(JdbcTemplate(dataSource)).loadOrCreate()
        assertEquals(original.keyID, reloaded.keyID)
        assertTrue(original.toRSAPrivateKey().encoded.contentEquals(reloaded.toRSAPrivateKey().encoded))
        assertEquals("restart-user", config.jwtDecoder(JWKSet(reloaded)).decode(token).subject)
        assertEquals(1, jdbc.queryForObject("select count(*) from mcp_signing_keys", Int::class.java))
    }

    @Test
    fun concurrentFirstStartupsAllUseTheSamePersistedKey() {
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(4).use { executor ->
            val attempts =
                (1..4).map {
                    executor.submit<String> {
                        start.await()
                        JdbcMcpSigningKeys(JdbcTemplate(dataSource)).loadOrCreate().keyID
                    }
                }
            start.countDown()
            assertEquals(1, attempts.map { it.get(20, TimeUnit.SECONDS) }.toSet().size)
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from mcp_signing_keys", Int::class.java))
    }

    @Test
    fun corruptStoredKeysFailInsteadOfSilentlyReplacingTheSigningIdentity() {
        jdbc.update("insert into mcp_signing_keys (key_name, private_jwk) values ('active', '{}')")
        assertFails { JdbcMcpSigningKeys(jdbc).loadOrCreate() }
        assertEquals("{}", jdbc.queryForObject("select private_jwk from mcp_signing_keys", String::class.java))
    }
}
