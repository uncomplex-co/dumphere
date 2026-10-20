package dev.uncomplex.dumphere.application

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

enum class ArtifactVisibility(
    @get:JsonValue val value: String,
) {
    INTERNAL("internal"),
    PUBLIC("public"),
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun parse(value: String): ArtifactVisibility =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("visibility must be internal or public")
    }
}
