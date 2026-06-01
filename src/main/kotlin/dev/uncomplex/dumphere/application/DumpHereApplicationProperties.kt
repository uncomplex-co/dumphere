package dev.uncomplex.dumphere.application

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "htmlshare")
data class DumpHereApplicationProperties(
    val storageDir: String,
    val publicBaseUrl: String,
    val maxHtmlBytes: Long,
    val allowedEmailDomain: String? = null,
    val cspScriptSrc: String = "'unsafe-inline'",
    val apiUsername: String,
    val apiPassword: String,
)
