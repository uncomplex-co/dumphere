package dev.uncomplex.htmlshare.htmlshare

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(HtmlshareProperties::class)
class HtmlshareApplication

fun main(args: Array<String>) {
    runApplication<HtmlshareApplication>(*args)
}
