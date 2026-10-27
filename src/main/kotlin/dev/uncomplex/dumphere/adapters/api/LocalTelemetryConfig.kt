package dev.uncomplex.dumphere.adapters.api

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.filter.OncePerRequestFilter

@Configuration(proxyBeanMethods = false)
@Profile("local_dev")
class LocalTelemetryConfig {
    @Bean
    fun telemetryLogAppender(openTelemetry: OpenTelemetry): InitializingBean =
        InitializingBean { OpenTelemetryAppender.install(openTelemetry) }

    @Bean
    fun authRequestLogging(): FilterRegistrationBean<OncePerRequestFilter> {
        val logger = LoggerFactory.getLogger("htmlshare.auth.requests")
        val filter =
            object : OncePerRequestFilter() {
                override fun shouldNotFilter(request: HttpServletRequest): Boolean =
                    request.requestURI != "/favicon.ico" && request.requestURI != "/login" &&
                        !request.requestURI.startsWith("/oauth2/") && !request.requestURI.startsWith("/login/")

                override fun doFilterInternal(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    filterChain: FilterChain,
                ) {
                    try {
                        filterChain.doFilter(request, response)
                    } finally {
                        logger.info("Auth HTTP request: method={} path={} status={}", request.method, request.requestURI, response.status)
                    }
                }
            }
        return FilterRegistrationBean<OncePerRequestFilter>(filter).apply { order = -101 }
    }
}
