package dev.uncomplex.dumphere

import dev.uncomplex.dumphere.application.SamePageAppProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(SamePageAppProperties::class)
class SamePageApp

fun main(args: Array<String>) {
    runApplication<SamePageApp>(*args)
}
