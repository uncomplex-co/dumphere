package dev.uncomplex.dumphere.ports

import dev.uncomplex.dumphere.application.UserEntity
import org.springframework.data.repository.CrudRepository

interface UserRepository : CrudRepository<UserEntity, Long> {
    fun findBySubject(subject: String): UserEntity?
}
