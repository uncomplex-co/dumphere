package dev.uncomplex.dumphere.adapters.mcp

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.ai.util.JacksonUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

@Configuration
class McpJsonConfig {
    @Bean(name = ["mcpServerJsonMapper"], defaultCandidate = false)
    fun mcpServerJsonMapper(): JsonMapper = JsonMapper.builder()
        .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .addModules(JacksonUtils.instantiateAvailableModules())
        // Omit absent protocol fields, but preserve explicit nulls inside tool argument maps.
        .changeDefaultPropertyInclusion {
            JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS)
        }
        .build()
}
