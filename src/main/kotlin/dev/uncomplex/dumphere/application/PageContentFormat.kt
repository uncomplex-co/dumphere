package dev.uncomplex.dumphere.application

enum class PageContentFormat {
    HTML,
    MARKDOWN,
    ;

    fun toolType(): String = name.lowercase()

    companion object {
        fun fromToolType(value: String): PageContentFormat =
            when (value.trim().lowercase()) {
                "html" -> HTML
                "markdown" -> MARKDOWN
                else -> error("unsupported format: $value")
            }
    }
}
