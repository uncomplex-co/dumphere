package dev.uncomplex.dumphere.application.mcp.tools

import dev.uncomplex.dumphere.application.UserEntity
import dev.uncomplex.dumphere.ports.PublishedArtifactsStore
import dev.uncomplex.dumphere.application.PageContentFormat
import dev.uncomplex.dumphere.application.PublishedPage
import org.springframework.stereotype.Service

@Service
class PublishHtmlTool(
    private val store: PublishedArtifactsStore,
) {
    fun execute(
        contents: String,
        format: String,
        title: String?,
        user: UserEntity?,
    ): PublishedPage = store.publish(title, contents, PageContentFormat.fromToolType(format), user)
}
