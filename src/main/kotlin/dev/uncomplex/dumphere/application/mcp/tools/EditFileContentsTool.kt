package dev.uncomplex.dumphere.application.mcp.tools

import dev.uncomplex.dumphere.application.AuthenticatedUser
import dev.uncomplex.dumphere.application.HtmlPageStore
import org.springframework.stereotype.Service

@Service
class EditFileContentsTool(
    private val store: HtmlPageStore,
) {
    fun execute(
        id: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
        user: AuthenticatedUser?,
    ): String {
        requireNotNull(store.edit(id, oldString, newString, replaceAll, user)) { "page not found: $id" }
        return "Edit applied successfully."
    }
}
