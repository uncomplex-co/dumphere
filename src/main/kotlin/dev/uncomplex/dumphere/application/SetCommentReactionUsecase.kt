package dev.uncomplex.dumphere.application

import dev.uncomplex.dumphere.ports.CommentReactionsRepository
import dev.uncomplex.dumphere.ports.CommentRepository
import dev.uncomplex.dumphere.ports.CommentThreadRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SetCommentReactionUsecase(
    private val comments: CommentRepository,
    private val threads: CommentThreadRepository,
    private val reactions: CommentReactionsRepository,
    private val reader: CommentReactions,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun execute(command: Command): Result {
        val existing = comments.findById(command.commentId).orElse(null) ?: return Result.NotFound
        val thread =
            threads
                .findByIdForUpdate(existing.threadId)
                ?.takeIf { it.artifactId == command.artifactId && it.deletedAt == null } ?: return Result.NotFound
        val comment =
            comments
                .findById(command.commentId)
                .orElse(null)
                ?.takeIf { it.deletedAt == null } ?: return Result.NotFound

        val user = command.author
        val changed =
            if (command.reacted) {
                reactions.add(requireNotNull(comment.id), requireNotNull(user.id), command.emoji)
            } else {
                reactions.remove(requireNotNull(comment.id), requireNotNull(user.id), command.emoji)
            }

        logger.info(
            "Comment reaction set: artifact={}, thread={}, comment={}, user={}, emoji={}, reacted={}, changed={}",
            thread.artifactId,
            thread.id,
            comment.id,
            user.id,
            command.emoji,
            command.reacted,
            changed > 0,
        )
        val current = reader.list(command.artifactId, command.author.subject)[command.commentId].orEmpty()
        return Result.Success(CommentReactionState(command.commentId, current))
    }

    data class Command(
        val artifactId: String,
        val commentId: Long,
        val emoji: String,
        val reacted: Boolean,
        val author: UserEntity,
    )

    sealed interface Result {
        data class Success(
            val state: CommentReactionState,
        ) : Result

        data object NotFound : Result
    }
}
