package dev.uncomplex.dumphere.application

import dev.uncomplex.dumphere.ports.CommentReactionsRepository
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.ReadOnlyProperty
import org.springframework.data.relational.core.mapping.Table
import org.springframework.stereotype.Service
import java.time.Instant

@Table("comment_reactions")
class CommentReactionEntity(
    var commentId: Long,
    var userId: Long,
    var emoji: String,
    @ReadOnlyProperty var createdAt: Instant? = null,
    @ReadOnlyProperty var updatedAt: Instant? = null,
    @Id var id: Long? = null,
)

data class CommentReactionVoter(val commentId: Long, val emoji: String, val name: String, val mine: Boolean)
data class CommentReaction(val emoji: String, val count: Int, val reacted: Boolean, val users: List<String>)
data class CommentReactionState(val commentId: Long, val reactions: List<CommentReaction>)

@Service
class CommentReactions(private val reactions: CommentReactionsRepository) {
    fun list(artifactId: String, subject: String?): Map<Long, List<CommentReaction>> =
        reactions.findArtifactVoters(artifactId, subject).groupBy { it.commentId }.mapValues { (_, voters) ->
            voters.groupBy { it.emoji }.map { (emoji, people) ->
                CommentReaction(emoji, people.size, people.any { it.mine }, people.map { it.name })
            }
        }
}
