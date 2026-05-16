package dev.uncomplex.dumphere

import dev.uncomplex.dumphere.application.DumpHereApplicationProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(DumpHereApplicationProperties::class)
class DumpHereApplication

fun main(args: Array<String>) {
    runApplication<DumpHereApplication>(*args)
}
