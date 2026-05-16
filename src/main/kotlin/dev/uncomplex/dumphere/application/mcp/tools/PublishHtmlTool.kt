package dev.uncomplex.dumphere.application.mcp.tools

import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.HtmlPageStore
import dev.uncomplex.dumphere.application.PublishedPage
import org.springframework.stereotype.Service

@Service
class PublishHtmlTool(
    private val store: HtmlPageStore,
) {
    fun execute(
        html: String,
        title: String?,
        user: AuthenticatedUser?,
    ): PublishedPage = store.publish(title, html, user)
}
