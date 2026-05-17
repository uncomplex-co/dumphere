package dev.uncomplex.dumphere.application.mcp.tools

import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.HtmlPageStore
import dev.uncomplex.dumphere.application.PublishedPage
import org.springframework.stereotype.Service

@Service
class SetPageLiveTool(
    private val store: HtmlPageStore,
) {
    fun execute(
        id: String,
        live: Boolean,
        user: AuthenticatedUser?,
    ): PublishedPage = requireNotNull(store.setLive(id, live, user)) { "page not found: $id" }
}
