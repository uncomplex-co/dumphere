package dev.uncomplex.dumphere.application

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "htmlshare")
data class DumpHereApplicationProperties(
    val publicBaseUrl: String,
    val maxHtmlBytes: Long,
    val allowedEmailDomain: String? = null,
    val cspScriptSrc: String = "'unsafe-inline' https://cdn.jsdelivr.net",
    val cspStyleSrc: String = "'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com",
    val cspFontSrc: String = "https://fonts.gstatic.com",
    val apiUsername: String,
    val apiPassword: String,
)
