package dev.uncomplex.htmlshare.htmlshare

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Component
class HtmlshareMcpTools(
    private val store: HtmlPageStore,
) {
    @McpTool(
        name = "publish_html",
        description = "Publish an HTML page for team sharing and return a link",
    )
    fun publishHtml(
        @McpToolParam(description = "Complete HTML document or fragment to publish", required = true)
        html: String,
        @McpToolParam(description = "Short human-readable page title", required = false)
        title: String?,
    ): PublishedPage = store.publish(title, html, SecurityContextHolder.getContext().authentication.authenticatedUser())

    @McpTool(
        name = "update_html",
        description = "Replace an existing published HTML page and return the same link",
    )
    fun updateHtml(
        @McpToolParam(description = "Published page id", required = true)
        id: String,
        @McpToolParam(description = "Replacement HTML document or fragment", required = true)
        html: String,
        @McpToolParam(description = "Optional replacement title", required = false)
        title: String?,
    ): PublishedPage = requireNotNull(store.update(id, title, html, SecurityContextHolder.getContext().authentication.authenticatedUser())) { "page not found: $id" }
}
