package dev.uncomplex.dumphere.application

import dev.uncomplex.dumphere.ports.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class UserProvisioningService(
    private val users: UserRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun provision(user: AuthenticatedUser): UserEntity {
        val existing = users.findBySubject(user.subject)
        val email = user.email ?: existing?.email
        val displayName = user.displayName ?: existing?.displayName
        if (existing != null && existing.email == email && existing.displayName == displayName) return existing

        val now = Instant.now()
        val resolved = existing ?: UserEntity(subject = user.subject, createdAt = now, updatedAt = now)
        resolved.email = email
        resolved.displayName = displayName
        resolved.updatedAt = now
        val saved = users.save(resolved)
        logger.info("User profile provisioned: user={}, created={}", saved.id, existing == null)
        return saved
    }
}
