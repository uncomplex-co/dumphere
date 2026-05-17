package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.application.PublishedPage
import dev.uncomplex.dumphere.application.authenticatedUser
import dev.uncomplex.dumphere.application.mcp.tools.EditFileContentsTool
import dev.uncomplex.dumphere.application.mcp.tools.PublishHtmlTool
import dev.uncomplex.dumphere.application.mcp.tools.ReadFileContentsTool
import dev.uncomplex.dumphere.application.mcp.tools.SetPageLiveTool
import dev.uncomplex.dumphere.application.mcp.tools.UpdateHtmlTool
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Component
class HtmlshareMcpTools(
    private val publishHtmlTool: PublishHtmlTool,
    private val readFileContentsTool: ReadFileContentsTool,
    private val updateHtmlTool: UpdateHtmlTool,
    private val editFileContentsTool: EditFileContentsTool,
    private val setPageLiveTool: SetPageLiveTool,
) {
    @McpTool(
        name = "publish_html",
        description = "Publish an HTML or Markdown page for team sharing and return a link",
    )
    fun publishHtml(
        @McpToolParam(description = "Complete HTML or Markdown document or fragment to publish", required = true)
        contents: String,
        @McpToolParam(description = "Content format: html or markdown", required = true)
        format: String,
        @McpToolParam(description = "Short human-readable file name", required = false)
        file_name: String?,
    ): PublishedPage = publishHtmlTool.execute(contents, format, file_name, currentUser())

    @McpTool(
        name = "read_file_contents",
        description =
            """
            Read a published HTML or Markdown file. If the page does not exist, an error is returned.

            Usage:
            - By default, this tool returns up to 2000 lines from the start of the file.
            - The offset parameter is the line number to start reading from (1-indexed).
            - To read later sections, call this tool again with a larger offset.
            - Contents are returned with each line prefixed by its line number as `<line>: <content>`.
            - Any line longer than 2000 characters is truncated.
            """,
    )
    fun readFileContents(
        @McpToolParam(description = "Published page id", required = true)
        id: String,
        @McpToolParam(description = "The line number to start reading from (1-indexed)", required = false)
        offset: Int?,
        @McpToolParam(description = "The maximum number of lines to read (defaults to 2000)", required = false)
        limit: Int?,
    ): String = readFileContentsTool.execute(id, offset, limit)

    @McpTool(
        name = "update_html",
        description = "Replace an existing published HTML or Markdown page contents and return the same link",
    )
    fun updateHtml(
        @McpToolParam(description = "Published page id", required = true)
        id: String,
        @McpToolParam(description = "Replacement HTML or Markdown document or fragment", required = true)
        contents: String,
    ): PublishedPage = updateHtmlTool.execute(id, contents, currentUser())

    @McpTool(
        name = "edit_file_contents",
        description =
            """
            Performs exact string replacements in a published HTML or Markdown file.

            Usage:
            - Read the page before editing so you can copy exact content and line context.
            - When editing text from read_file_contents output, never include the line number prefix in oldString or newString.
            - The edit fails if oldString is not found in the current file.
            - The edit fails if oldString matches multiple times and replaceAll is not true.
            - Use replaceAll for renaming or replacing every occurrence.
            """,
    )
    fun editFileContents(
        @McpToolParam(description = "Published page id", required = true)
        id: String,
        @McpToolParam(description = "The text to replace", required = true)
        oldString: String,
        @McpToolParam(description = "The text to replace it with (must be different from oldString)", required = true)
        newString: String,
        @McpToolParam(description = "Replace all occurrences of oldString (default false)", required = false)
        replaceAll: Boolean?,
    ): String = editFileContentsTool.execute(id, oldString, newString, replaceAll ?: false, currentUser())

    @McpTool(
        name = "enable_live_reload",
        description = "Enable or disable live-reload for a published page. When enabled, the page automatically refreshes after each edit.",
    )
    fun enableLiveReload(
        @McpToolParam(description = "Published page id", required = true)
        id: String,
        @McpToolParam(description = "Enable live reload", required = true)
        live: Boolean,
    ): PublishedPage = setPageLiveTool.execute(id, live, currentUser())

    private fun currentUser() = SecurityContextHolder.getContext().authentication.authenticatedUser()
}
