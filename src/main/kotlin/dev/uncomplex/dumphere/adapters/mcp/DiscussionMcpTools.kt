package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.application.ArtifactComment
import dev.uncomplex.dumphere.application.ArtifactCommentThread
import dev.uncomplex.dumphere.application.ArtifactComments
import dev.uncomplex.dumphere.application.HtmlPageStore
import dev.uncomplex.dumphere.application.authenticatedUser
import dev.uncomplex.dumphere.ports.CommentThreadRepository
import dev.uncomplex.dumphere.ports.HtmlPageVersionRepository
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Component
class DiscussionMcpTools(
    private val comments: ArtifactComments,
    private val pages: HtmlPageStore,
    private val threads: CommentThreadRepository,
    private val versions: HtmlPageVersionRepository,
) {
    @McpTool(
        name = "list_discussion_threads",
        description = "List all discussion threads for an artifact, newest activity first. Includes first comment text, reply count, author, and any element anchor. Threads persist across artifact versions.",
    )
    fun listDiscussionThreads(
        @McpToolParam(description = "Published artifact id", required = true) id: String,
    ): List<ArtifactCommentThread> {
        requirePage(id)
        return comments.listThreads(id)
    }

    @McpTool(
        name = "list_thread_comments",
        description = "List the first comment and all replies in an artifact discussion thread, oldest first.",
    )
    fun listThreadComments(
        @McpToolParam(description = "Published artifact id", required = true) id: String,
        @McpToolParam(description = "Discussion thread id", required = true) thread_id: Long,
    ): List<ArtifactComment> {
        requirePage(id)
        requireThread(id, thread_id)
        return comments.listThread(id, thread_id)
    }

    @McpTool(
        name = "start_discussion_thread",
        description = "Start a document-level discussion thread as the authenticated user. Does not modify artifact content.",
    )
    fun startDiscussionThread(
        @McpToolParam(description = "Published artifact id", required = true) id: String,
        @McpToolParam(description = "Plain text comment, 1 to 10000 characters after trimming", required = true) body: String,
    ): ArtifactComment = comments.add(id, currentVersionId(id), validatedBody(body), currentUser())

    @McpTool(
        name = "reply_to_thread",
        description = "Reply to an existing artifact discussion thread as the authenticated user.",
    )
    fun replyToThread(
        @McpToolParam(description = "Published artifact id", required = true) id: String,
        @McpToolParam(description = "Discussion thread id", required = true) thread_id: Long,
        @McpToolParam(description = "Plain text reply, 1 to 10000 characters after trimming", required = true) body: String,
    ): ArtifactComment {
        val versionId = currentVersionId(id)
        return comments.reply(requireThread(id, thread_id), versionId, validatedBody(body), currentUser())
    }

    private fun requirePage(id: String) = requireNotNull(pages.readMetadata(id)) { "page not found: $id" }

    private fun requireThread(id: String, threadId: Long) =
        requireNotNull(threads.findById(threadId).orElse(null)?.takeIf { it.artifactId == id && it.deletedAt == null }) { "thread not found: $threadId" }

    private fun currentVersionId(id: String): Long {
        val page = requirePage(id)
        return requireNotNull(versions.findByPageIdAndVersion(id, page.version)?.id) { "Artifact version not found" }
    }

    private fun validatedBody(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 10000) { "Comment must contain between 1 and 10000 characters" }
    }

    private fun currentUser() = requireNotNull(SecurityContextHolder.getContext().authentication.authenticatedUser()) {
        "Authentication required"
    }
}
