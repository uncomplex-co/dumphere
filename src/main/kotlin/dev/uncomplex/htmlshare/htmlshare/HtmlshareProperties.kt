package dev.uncomplex.htmlshare.htmlshare

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "htmlshare")
data class HtmlshareProperties(
    val storageDir: String,
    val publicBaseUrl: String,
    val maxHtmlBytes: Long,
    val allowedEmailDomain: String? = null,
)
