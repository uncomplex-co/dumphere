package dev.uncomplex.dumphere.adapters

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class ReactionEmojiCatalog(mapper: ObjectMapper) {
    private val allowed = ClassPathResource("static/viewer/vendor/emoji-mart-data.json").inputStream.use { stream ->
        mapper.readTree(stream).path("emojis").properties().map { it.value.path("skins")[0].path("native").asString() }.toSet()
    }

    fun contains(emoji: String): Boolean = emoji in allowed
}
