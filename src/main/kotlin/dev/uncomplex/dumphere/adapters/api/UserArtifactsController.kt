package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.authenticatedUser
import dev.uncomplex.dumphere.ports.HtmlPageRepository
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
class UserArtifactsController(
    private val pages: HtmlPageRepository,
) {
    @GetMapping("/account/artifacts")
    fun list(
        @RequestParam(defaultValue = "") excludeId: String,
        authentication: Authentication?,
    ): ResponseEntity<List<CreatedArtifactResponse>> {
        val user = authentication.authenticatedUser() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val artifacts =
            pages.findOtherArtifactsByCreator(user.subject, excludeId).map {
                CreatedArtifactResponse(it.id, it.title, it.contentFormat.toolType(), it.createdAt)
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(artifacts)
    }
}

data class CreatedArtifactResponse(
    val id: String,
    val title: String,
    val format: String,
    val createdAt: Instant,
)
