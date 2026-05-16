package dev.uncomplex.dumphere.application

import dev.uncomplex.dumphere.ports.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class UserProvisioningService(
    private val users: UserRepository,
) {
    @Transactional
    fun provision(user: AuthenticatedUser): UserEntity {
        val now = Instant.now()
        val existing = users.findBySubject(user.subject)
        return users.save(
            existing?.copy(
                email = user.email ?: existing.email,
                displayName = user.displayName ?: existing.displayName,
                updatedAt = now,
            ) ?: UserEntity(
                subject = user.subject,
                email = user.email,
                displayName = user.displayName,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
