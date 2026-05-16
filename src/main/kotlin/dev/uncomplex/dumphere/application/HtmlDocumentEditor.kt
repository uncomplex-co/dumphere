package dev.uncomplex.dumphere.application

import kotlin.math.max
import kotlin.math.min

object HtmlDocumentEditor {
    fun replace(
        content: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean = false,
    ): String {
        require(oldString != newString) {
            "No changes to apply: oldString and newString are identical."
        }

        if (oldString.isEmpty()) {
            return newString
        }

        var notFound = true
        for (replacer in REPLACERS) {
            for (search in replacer(content, oldString)) {
                val firstIndex = content.indexOf(search)
                if (firstIndex == -1) {
                    continue
                }

                notFound = false
                if (replaceAll) {
                    return content.replace(search, newString)
                }

                val lastIndex = content.lastIndexOf(search)
                if (firstIndex != lastIndex) {
                    continue
                }

                return content.substring(0, firstIndex) + newString + content.substring(firstIndex + search.length)
            }
        }

        if (notFound) {
            error("Could not find oldString in the file. It must match exactly, including whitespace, indentation, and line endings.")
        }

        error("Found multiple matches for oldString. Provide more surrounding context to make the match unique.")
    }

    fun applyWithOriginalLineEndings(
        content: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean = false,
    ): String {
        if (oldString.isEmpty()) {
            return newString
        }

        val lineEnding = detectLineEnding(content)
        val normalizedOldString = convertToLineEnding(normalizeLineEndings(oldString), lineEnding)
        val normalizedNewString = convertToLineEnding(normalizeLineEndings(newString), lineEnding)
        return replace(content, normalizedOldString, normalizedNewString, replaceAll)
    }

    private val REPLACERS =
        listOf(
            ::simpleReplacer,
            ::lineTrimmedReplacer,
            ::blockAnchorReplacer,
            ::whitespaceNormalizedReplacer,
            ::indentationFlexibleReplacer,
            ::escapeNormalizedReplacer,
            ::trimmedBoundaryReplacer,
            ::contextAwareReplacer,
            ::multiOccurrenceReplacer,
        )

    private fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n")

    private fun detectLineEnding(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"

    private fun convertToLineEnding(
        text: String,
        ending: String,
    ): String =
        if (ending == "\n") {
            text
        } else {
            text.replace("\n", "\r\n")
        }

    private fun simpleReplacer(
        content: String,
        find: String,
    ): Sequence<String> =
        sequence {
            if (content.contains(find)) {
                yield(find)
            }
        }

    private fun lineTrimmedReplacer(
        content: String,
        find: String,
    ): Sequence<String> =
        sequence {
            val originalLines = content.split("\n")
            val searchLines = find.split("\n").toMutableList()
            if (searchLines.lastOrNull().isNullOrEmpty()) {
                searchLines.removeLast()
            }
            if (searchLines.isEmpty()) {
                return@sequence
            }

            for (start in 0..originalLines.size - searchLines.size) {
                var matches = true
                for (index in searchLines.indices) {
                    if (originalLines[start + index].trim() != searchLines[index].trim()) {
                        matches = false
                        break
                    }
                }

                if (!matches) {
                    continue
                }

                val matchStartIndex = prefixLength(originalLines, start)
                val matchEndIndex = blockEndIndex(originalLines, start, searchLines.size)
                yield(content.substring(matchStartIndex, matchEndIndex))
            }
        }

    private fun blockAnchorReplacer(
        content: String,
        find: String,
    ): Sequence<String> =
        sequence {
            val originalLines = content.split("\n")
            val searchLines = find.split("\n").toMutableList()
            if (searchLines.size < 3) {
                return@sequence
            }
            if (searchLines.lastOrNull().isNullOrEmpty()) {
                searchLines.removeLast()
            }

            val firstLineSearch = searchLines.first().trim()
            val lastLineSearch = searchLines.last().trim()
            val candidates = mutableListOf<Pair<Int, Int>>()

            for (start in originalLines.indices) {
                if (originalLines[start].trim() != firstLineSearch) {
                    continue
                }

                for (end in start + 2 until originalLines.size) {
                    if (originalLines[end].trim() == lastLineSearch) {
                        candidates += start to end
                        break
                    }
                }
            }

            if (candidates.isEmpty()) {
                return@sequence
            }

            if (candidates.size == 1) {
                val (start, end) = candidates.single()
                val actualBlockSize = end - start + 1
                val similarity =
                    similarity(
                        originalLines = originalLines,
                        searchLines = searchLines,
                        startLine = start,
                        actualBlockSize = actualBlockSize,
                    )

                if (similarity >= 0.0) {
                    val matchStartIndex = prefixLength(originalLines, start)
                    val matchEndIndex = blockEndIndex(originalLines, start, actualBlockSize)
                    yield(content.substring(matchStartIndex, matchEndIndex))
                }
                return@sequence
            }

            var bestMatch: Pair<Int, Int>? = null
            var maxSimilarity = -1.0
            for ((start, end) in candidates) {
                val actualBlockSize = end - start + 1
                val similarity = similarity(originalLines, searchLines, start, actualBlockSize)
                if (similarity > maxSimilarity) {
                    maxSimilarity = similarity
                    bestMatch = start to end
                }
            }

            if (maxSimilarity >= 0.3 && bestMatch != null) {
                val (start, end) = bestMatch
                val matchStartIndex = prefixLength(originalLines, start)
                val matchEndIndex = blockEndIndex(originalLines, start, end - start + 1)
                yield(content.substring(matchStartIndex, matchEndIndex))
            }
        }

    private fun whitespaceNormalizedReplacer(
        content: String,
        find: String,
    ): Sequence<String> =
        sequence {
            fun normalizeWhitespace(text: String) = text.replace(Regex("\\s+"), " ").trim()

            val normalizedFind = normalizeWhitespace(find)
            val lines = content.split("\n")
            for (line in lines) {
                if (normalizeWhitespace(line) == normalizedFind) {
                    yield(line)
                    continue
                }

                val normalizedLine = normalizeWhitespace(line)
                if (!normalizedLine.contains(normalizedFind)) {
                    continue
                }

                val words = find.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.isEmpty()) {
                    continue
                }

                val pattern = words.joinToString("\\s+") { Regex.escape(it) }
                val match = Regex(pattern).find(line)
                if (match != null) {
                    yield(match.value)
                }
            }

            val findLines = find.split("\n")
            if (findLines.size <= 1) {
                return@sequence
            }

            for (start in 0..lines.size - findLines.size) {
                val block = lines.subList(start, start + findLines.size).joinToString("\n")
                if (normalizeWhitespace(block) == normalizedFind) {
                    yield(block)
                }
            }
        }

    private fun indentationFlexibleReplacer(
        content: String,
        find: String,
    ): Sequence<String> =
        sequence {
            fun removeIndentation(text: String): String {
                val lines = text.split("\n")
                val nonEmptyLines = lines.filter { it.trim().isNotEmpty() }
                if (nonEmptyLines.isEmpty()) {
                    return text
                }

                val minIndent = nonEmptyLines.minOf { line -> line.takeWhile { it.isWhitespace() }.length }
                return lines.joinToString("\n") { line ->
                    if (line.trim().isEmpty()) {
                        line
                    } else {
                        line.drop(minIndent)
                    }
                }
            }

            val normalizedFind = removeIndentation(find)
            val contentLines = content.split("\n")
            val findLines = find.split("\n")
            if (findLines.isEmpty() || contentLines.size < findLines.size) {
                return@sequence
            }

            for (start in 0..contentLines.size - findLines.size) {
                val block = contentLines.subList(start, start + findLines.size).joinToString("\n")
                if (removeIndentation(block) == normalizedFind) {
                    yield(block)
                }
            }
        }

    private fun escapeNormalizedReplacer(
        content: String,
        find: String,
    ): Sequence<String> =
        sequence {
            fun unescapeString(text: String): String =
                text.replace(Regex("\\\\(n|t|r|'|\"|`|\\\\|\\n|\\$)")) { match ->
                    when (match.groupValues[1]) {
                        "n" -> "\n"
                        "t" -> "\t"
                        "r" -> "\r"
                        "'" -> "'"
                        "\"" -> "\""
                        "`" -> "`"
                        "\\" -> "\\"
                        "\n" -> "\n"
                        "$" -> "$"
                        else -> match.value
                    }
                }

            val unescapedFind = unescapeString(find)
            if (content.contains(unescapedFind)) {
                yield(unescapedFind)
            }

            val lines = content.split("\n")
            val findLines = unescapedFind.split("\n")
            for (start in 0..lines.size - findLines.size) {
                val block = lines.subList(start, start + findLines.size).joinToString("\n")
                if (unescapeString(block) == unescapedFind) {
                    yield(block)
                }
            }
        }

    private fun multiOccurrenceReplacer(
        content: String,
        find: String,
    ): Sequence<String> =
        sequence {
            var startIndex = 0
            while (true) {
                val index = content.indexOf(find, startIndex)
                if (index == -1) {
                    break
                }

                yield(find)
                startIndex = index + find.length
            }
        }

    private fun trimmedBoundaryReplacer(
        content: String,
        find: String,
    ): Sequence<String> =
        sequence {
            val trimmedFind = find.trim()
            if (trimmedFind == find) {
                return@sequence
            }

            if (content.contains(trimmedFind)) {
                yield(trimmedFind)
            }

            val lines = content.split("\n")
            val findLines = find.split("\n")
            for (start in 0..lines.size - findLines.size) {
                val block = lines.subList(start, start + findLines.size).joinToString("\n")
                if (block.trim() == trimmedFind) {
                    yield(block)
                }
            }
        }

    private fun contextAwareReplacer(
        content: String,
        find: String,
    ): Sequence<String> =
        sequence {
            val findLines = find.split("\n").toMutableList()
            if (findLines.size < 3) {
                return@sequence
            }
            if (findLines.lastOrNull().isNullOrEmpty()) {
                findLines.removeLast()
            }

            val contentLines = content.split("\n")
            val firstLine = findLines.first().trim()
            val lastLine = findLines.last().trim()
            for (start in contentLines.indices) {
                if (contentLines[start].trim() != firstLine) {
                    continue
                }

                for (end in start + 2 until contentLines.size) {
                    if (contentLines[end].trim() != lastLine) {
                        continue
                    }

                    val blockLines = contentLines.subList(start, end + 1)
                    if (blockLines.size == findLines.size && contextSimilarity(blockLines, findLines) >= 0.5) {
                        yield(blockLines.joinToString("\n"))
                        break
                    }
                    break
                }
            }
        }

    private fun similarity(
        originalLines: List<String>,
        searchLines: List<String>,
        startLine: Int,
        actualBlockSize: Int,
    ): Double {
        val linesToCheck = min(searchLines.size - 2, actualBlockSize - 2)
        if (linesToCheck <= 0) {
            return 1.0
        }

        var similarity = 0.0
        for (index in 1 until min(searchLines.size - 1, actualBlockSize - 1)) {
            val originalLine = originalLines[startLine + index].trim()
            val searchLine = searchLines[index].trim()
            val maxLength = max(originalLine.length, searchLine.length)
            if (maxLength == 0) {
                continue
            }

            val distance = levenshtein(originalLine, searchLine)
            similarity += 1 - distance.toDouble() / maxLength.toDouble()
        }

        return similarity / linesToCheck.toDouble()
    }

    private fun contextSimilarity(
        blockLines: List<String>,
        findLines: List<String>,
    ): Double {
        var matchingLines = 0
        var totalNonEmptyLines = 0
        for (index in 1 until blockLines.lastIndex) {
            val blockLine = blockLines[index].trim()
            val findLine = findLines[index].trim()
            if (blockLine.isNotEmpty() || findLine.isNotEmpty()) {
                totalNonEmptyLines++
                if (blockLine == findLine) {
                    matchingLines++
                }
            }
        }

        if (totalNonEmptyLines == 0) {
            return 1.0
        }

        return matchingLines.toDouble() / totalNonEmptyLines.toDouble()
    }

    private fun prefixLength(
        lines: List<String>,
        lineCount: Int,
    ): Int = lines.take(lineCount).sumOf { it.length + 1 }

    private fun blockEndIndex(
        lines: List<String>,
        startLine: Int,
        lineCount: Int,
    ): Int {
        var end = prefixLength(lines, startLine)
        for (index in 0 until lineCount) {
            end += lines[startLine + index].length
            if (index < lineCount - 1) {
                end += 1
            }
        }
        return end
    }

    private fun levenshtein(
        a: String,
        b: String,
    ): Int {
        if (a.isEmpty() || b.isEmpty()) {
            return max(a.length, b.length)
        }

        val matrix =
            Array(a.length + 1) { i ->
                IntArray(b.length + 1) { j ->
                    if (i == 0) {
                        j
                    } else if (j == 0) {
                        i
                    } else {
                        0
                    }
                }
            }
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                matrix[i][j] =
                    min(
                        min(matrix[i - 1][j] + 1, matrix[i][j - 1] + 1),
                        matrix[i - 1][j - 1] + cost,
                    )
            }
        }

        return matrix[a.length][b.length]
    }
}
