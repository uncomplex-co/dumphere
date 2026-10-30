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
        val resolved = existing ?: UserEntity(subject = user.subject, createdAt = now, updatedAt = now)
        resolved.email = user.email ?: resolved.email
        resolved.displayName = user.displayName ?: resolved.displayName
        resolved.updatedAt = now
        return users.save(resolved)
    }
}
