package dev.uncomplex.dumphere.application.mcp.tools

import dev.uncomplex.dumphere.application.UserEntity
import dev.uncomplex.dumphere.application.HtmlPageStore
import dev.uncomplex.dumphere.application.PublishedPage
import org.springframework.stereotype.Service

@Service
class UpdateHtmlTool(
    private val store: HtmlPageStore,
) {
    fun execute(
        id: String,
        contents: String,
        user: UserEntity?,
    ): PublishedPage = requireNotNull(store.update(id, contents, user)) { "page not found: $id" }
}
