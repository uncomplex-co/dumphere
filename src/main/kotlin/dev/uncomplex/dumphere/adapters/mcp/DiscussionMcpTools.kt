package dev.uncomplex.dumphere.adapters.mcp

import dev.uncomplex.dumphere.adapters.ReactionEmojiCatalog
import dev.uncomplex.dumphere.application.ArtifactComment
import dev.uncomplex.dumphere.application.ArtifactComments
import dev.uncomplex.dumphere.ports.PublishedArtifactsStore
import dev.uncomplex.dumphere.application.DiscussionThreadState
import dev.uncomplex.dumphere.adapters.authentication.AuthenticatedUsers
import dev.uncomplex.dumphere.application.SetCommentReactionUsecase
import dev.uncomplex.dumphere.ports.CommentThreadRepository
import dev.uncomplex.dumphere.ports.HtmlPageVersionRepository
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Component
class DiscussionMcpTools(
    private val comments: ArtifactComments,
    private val pages: PublishedArtifactsStore,
    private val threads: CommentThreadRepository,
    private val versions: HtmlPageVersionRepository,
    private val users: AuthenticatedUsers,
    private val setReaction: SetCommentReactionUsecase,
    private val emojiCatalog: ReactionEmojiCatalog,
) {
    @McpTool(
        name = "list_discussion_threads",
        description = "List an artifact's discussion threads.",
    )
    fun listDiscussionThreads(
        @McpToolParam(description = "Published artifact id", required = true) id: String,
    ): List<DiscussionThread> {
        requirePage(id)
        return comments.listThreads(id).map(DiscussionThread::from)
    }

    @McpTool(
        name = "list_thread_comments",
        description = "Read a discussion thread's comments, replies, and reactions.",
    )
    fun listThreadComments(
        @McpToolParam(description = "Published artifact id", required = true) id: String,
        @McpToolParam(description = "Discussion thread id", required = true) thread_id: Long,
    ): List<DiscussionComment> {
        requirePage(id)
        requireThread(id, thread_id)
        return comments.listThread(id, thread_id).map(DiscussionComment::from)
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

    @McpTool(name = "add_comment_reaction", description = "Add your emoji reaction to a comment.")
    fun addCommentReaction(
        @McpToolParam(description = "Published artifact id", required = true) id: String,
        @McpToolParam(description = "Comment id", required = true) comment_id: Long,
        @McpToolParam(description = "Emoji without a skin-tone modifier", required = true) emoji: String,
    ): DiscussionReactions = react(id, comment_id, emoji, true)

    @McpTool(name = "remove_comment_reaction", description = "Remove your emoji reaction from a comment.")
    fun removeCommentReaction(
        @McpToolParam(description = "Published artifact id", required = true) id: String,
        @McpToolParam(description = "Comment id", required = true) comment_id: Long,
        @McpToolParam(description = "Emoji without a skin-tone modifier", required = true) emoji: String,
    ): DiscussionReactions = react(id, comment_id, emoji, false)

    private fun react(id: String, commentId: Long, emoji: String, reacted: Boolean): DiscussionReactions {
        requirePage(id)
        require(emojiCatalog.contains(emoji)) { "Unsupported reaction emoji" }
        return when (val result = setReaction.execute(SetCommentReactionUsecase.Command(id, commentId, emoji, reacted, currentUser()))) {
            is SetCommentReactionUsecase.Result.Success -> DiscussionReactions(result.state.commentId, DiscussionReaction.from(result.state.reactions))
            SetCommentReactionUsecase.Result.NotFound -> throw IllegalArgumentException("comment not found: $commentId")
        }
    }

    @McpTool(name = "resolve_discussion_thread", description = "Resolve a discussion thread without deleting its comments. Already-resolved threads are unchanged.")
    fun resolveDiscussionThread(
        @McpToolParam(description = "Published artifact id", required = true) id: String,
        @McpToolParam(description = "Discussion thread id", required = true) thread_id: Long,
    ): DiscussionThreadState {
        requirePage(id)
        return requireNotNull(comments.setResolved(id, thread_id, true, currentUser())) { "thread not found: $thread_id" }
    }

    @McpTool(name = "reopen_discussion_thread", description = "Reopen a resolved discussion thread, preserving comments and its stable thread number. Already-open threads are unchanged.")
    fun reopenDiscussionThread(
        @McpToolParam(description = "Published artifact id", required = true) id: String,
        @McpToolParam(description = "Discussion thread id", required = true) thread_id: Long,
    ): DiscussionThreadState {
        requirePage(id)
        return requireNotNull(comments.setResolved(id, thread_id, false, currentUser())) { "thread not found: $thread_id" }
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

    private fun currentUser() = requireNotNull(users.resolve(SecurityContextHolder.getContext().authentication)) {
        "Authentication required"
    }
}
