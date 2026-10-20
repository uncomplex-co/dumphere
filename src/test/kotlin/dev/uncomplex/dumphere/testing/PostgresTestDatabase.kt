package dev.uncomplex.dumphere.testing

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.postgresql.PostgreSQLContainer

object PostgresTestDatabase {
    private val database = PostgreSQLContainer("postgres:17-alpine").apply { start() }

    fun configure(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url", database::getJdbcUrl)
        registry.add("spring.datasource.username", database::getUsername)
        registry.add("spring.datasource.password", database::getPassword)
        registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
        registry.add("spring.flyway.enabled") { "true" }
        registry.add("spring.docker.compose.enabled") { "false" }
    }
}
