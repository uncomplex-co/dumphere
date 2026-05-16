package dev.uncomplex.dumphere

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://accounts.google.com",
    ],
)
class DumpHereApplicationTests {
    @Test
    fun contextLoads() {
    }
}
