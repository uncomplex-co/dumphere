package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.authenticatedUser
import dev.uncomplex.dumphere.application.mcp.tools.EditFileContentsTool
import dev.uncomplex.dumphere.application.mcp.tools.ReadFileContentsTool
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/agent/pages")
class AgentApiController(
    private val readFileContentsTool: ReadFileContentsTool,
    private val editFileContentsTool: EditFileContentsTool,
) {
    @GetMapping("/{id}/contents")
    fun contents(
        @PathVariable id: String,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): String = readFileContentsTool.execute(id, offset, limit)

    @PostMapping("/{id}/edit")
    fun edit(
        @PathVariable id: String,
        @RequestBody request: EditFileContentsRequest,
        authentication: Authentication,
    ): String =
        editFileContentsTool.execute(
            id,
            request.oldString,
            request.newString,
            request.replaceAll ?: false,
            authentication.authenticatedUser(),
        )
}

data class EditFileContentsRequest(
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean? = null,
)
