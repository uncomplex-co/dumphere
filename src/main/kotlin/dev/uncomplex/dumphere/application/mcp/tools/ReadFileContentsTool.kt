package dev.uncomplex.dumphere.application.mcp.tools

import dev.uncomplex.dumphere.application.HtmlPageStore
import org.springframework.stereotype.Service

@Service
class ReadFileContentsTool(
    private val store: HtmlPageStore,
) {
    fun execute(
        id: String,
        offset: Int?,
        limit: Int?,
    ): String {
        val actualOffset = offset ?: DEFAULT_READ_LIMIT_OFFSET
        val actualLimit = limit ?: DEFAULT_READ_LIMIT
        require(actualOffset >= 1) { "offset must be at least 1" }
        require(actualLimit >= 1) { "limit must be at least 1" }

        val page = requireNotNull(store.readMetadata(id)) { "page not found: $id" }
        val contents = requireNotNull(store.readContents(id)) { "page not found: $id" }
        val slice = sliceLines(contents, actualLimit, actualOffset)
        if (slice.count < actualOffset && !(slice.count == 0 && actualOffset == 1)) {
            error("Offset $actualOffset is out of range for this file (${slice.count} lines)")
        }

        return buildString {
            append("<path>$id</path>\n")
            append("<type>${page.contentFormat.toolType()}</type>\n")
            append("<content>\n")
            append(slice.lines.mapIndexed { index, line -> "${index + actualOffset}: $line" }.joinToString("\n"))

            val last = actualOffset + slice.lines.size - 1
            val next = last + 1
            when {
                slice.cut -> {
                    append(
                        "\n\n(Output capped at $MAX_BYTES_LABEL. Showing lines $actualOffset-$last. Use offset=$next to continue.)",
                    )
                }

                slice.more -> {
                    append("\n\n(Showing lines $actualOffset-$last of ${slice.count}. Use offset=$next to continue.)")
                }

                else -> {
                    append("\n\n(End of file - total ${slice.count} lines)")
                }
            }

            append("\n</content>")
        }
    }

    private fun sliceLines(
        content: String,
        limit: Int,
        offset: Int,
    ): ReadSlice {
        val start = offset - 1
        val raw = mutableListOf<String>()
        var bytes = 0
        var count = 0
        var cut = false
        var more = false

        for (line in splitLines(content)) {
            count += 1
            if (count <= start) {
                continue
            }

            if (raw.size >= limit) {
                more = true
                continue
            }

            val rendered = if (line.length > MAX_LINE_LENGTH) line.take(MAX_LINE_LENGTH) + MAX_LINE_SUFFIX else line
            val size = rendered.toByteArray(Charsets.UTF_8).size + if (raw.isEmpty()) 0 else 1
            if (bytes + size > MAX_BYTES) {
                cut = true
                more = true
                break
            }

            raw += rendered
            bytes += size
        }

        return ReadSlice(raw, count, cut, more)
    }

    private fun splitLines(content: String): List<String> {
        if (content.isEmpty()) {
            return emptyList()
        }

        val normalized = content.replace("\r\n", "\n")
        val lines = mutableListOf<String>()
        var start = 0
        for (index in normalized.indices) {
            if (normalized[index] != '\n') {
                continue
            }

            lines += normalized.substring(start, index)
            start = index + 1
        }

        if (start < normalized.length) {
            lines += normalized.substring(start)
        }

        return lines
    }

    private data class ReadSlice(
        val lines: List<String>,
        val count: Int,
        val cut: Boolean,
        val more: Boolean,
    )

    private companion object {
        const val DEFAULT_READ_LIMIT_OFFSET = 1
        const val DEFAULT_READ_LIMIT = 2000
        const val MAX_LINE_LENGTH = 2000
        const val MAX_LINE_SUFFIX = "... (line truncated to 2000 chars)"
        const val MAX_BYTES = 50 * 1024
        const val MAX_BYTES_LABEL = "50 KB"
    }
}
