package dev.uncomplex.dumphere.adapters.mcp

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64

data class McpRefreshTokenGrant(
    val userId: Long,
    val clientId: String,
    val scopes: Set<String>,
    val expiresAt: Instant,
)

@Repository
class JdbcMcpRefreshTokens(
    private val jdbc: JdbcTemplate,
) {
    fun save(
        token: String,
        grant: McpRefreshTokenGrant,
    ) {
        jdbc.update(
            """
            insert into mcp_refresh_tokens (token_hash, client_id, user_id, scopes, expires_at)
            values (?, ?, ?, ?, ?)
            """.trimIndent(),
            hash(token),
            grant.clientId,
            grant.userId,
            grant.scopes.joinToString(" "),
            Timestamp.from(grant.expiresAt),
        )
    }

    fun consume(
        token: String,
        clientId: String,
        now: Instant,
    ): McpRefreshTokenGrant? =
        jdbc
            .query(
                """
                delete from mcp_refresh_tokens
                where token_hash = ? and client_id = ? and expires_at > ?
                returning user_id, client_id, scopes, expires_at
                """.trimIndent(),
                { row, _ ->
                    McpRefreshTokenGrant(
                        userId = row.getLong("user_id"),
                        clientId = row.getString("client_id"),
                        scopes =
                            row
                                .getString("scopes")
                                .split(' ')
                                .filter { it.isNotBlank() }
                                .toSet(),
                        expiresAt = row.getTimestamp("expires_at").toInstant(),
                    )
                },
                hash(token),
                clientId,
                Timestamp.from(now),
            ).singleOrNull()

    fun deleteExpired(now: Instant) {
        jdbc.update("delete from mcp_refresh_tokens where expires_at <= ?", Timestamp.from(now))
    }

    private fun hash(token: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )
}
