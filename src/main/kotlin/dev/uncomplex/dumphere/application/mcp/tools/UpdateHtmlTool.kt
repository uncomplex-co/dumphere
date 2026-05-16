package dev.uncomplex.dumphere.application.mcp.tools

import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.HtmlPageStore
import dev.uncomplex.dumphere.application.PublishedPage
import org.springframework.stereotype.Service

@Service
class UpdateHtmlTool(
    private val store: HtmlPageStore,
) {
    fun execute(
        id: String,
        html: String,
        user: AuthenticatedUser?,
    ): PublishedPage = requireNotNull(store.update(id, html, user)) { "page not found: $id" }
}
