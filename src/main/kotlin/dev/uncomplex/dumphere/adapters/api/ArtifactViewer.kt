package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.PageContentFormat
import dev.uncomplex.dumphere.application.PublishedPage
import dev.uncomplex.dumphere.application.authenticatedUser
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils

object ArtifactViewerIds {
    const val DOWNLOAD = "artifact-download-button"
    const val SHARE = "artifact-share-button"
    const val AVATAR = "account-avatar"
    const val FRAME = "artifact-content-frame"
    const val INTERNAL = "share-internal-radio"
    const val PUBLIC = "share-public-radio"
    const val COPY = "share-copy-link-button"
    const val URL = "share-url-input"
    const val SAVE = "share-save-button"
    const val CANCEL = "share-cancel-button"
    const val CLOSE = "share-close-button"
}

@Component
class ArtifactViewer {
    private val template = requireNotNull(javaClass.getResource("/templates/artifact-viewer.html")).readText()

    fun render(
        page: PublishedPage?,
        authentication: Authentication?,
        canManage: Boolean,
        csrf: String,
    ): String {
        val user = authentication.takeIf { canManage }.authenticatedUser()
        val name = user?.displayName ?: user?.email ?: "Team member"
        val picture =
            when (val principal = authentication?.principal.takeIf { canManage }) {
                is OAuth2AuthenticatedPrincipal -> principal.getAttribute<String>("picture")
                is Jwt -> principal.getClaimAsString("picture")
                else -> null
            }?.takeIf { it.startsWith("https://") }
        val initials =
            name
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.take(1) }
                .uppercase()
        val values =
            mapOf(
                "TITLE" to (page?.title ?: "Internal Artifact"),
                "ID" to (page?.id ?: ""),
                "FORMAT" to (page?.contentFormat?.toolType() ?: "html"),
                "FORMAT_LABEL" to if (page?.contentFormat == PageContentFormat.MARKDOWN) "Markdown" else "HTML",
                "VISIBILITY" to (page?.visibility?.value ?: "internal"),
                "PUBLIC_UNTIL" to (page?.publicUntil?.toString() ?: ""),
                "VERSION" to (page?.version?.toString() ?: "0"),
                "CAN_VIEW" to (page != null).toString(),
                "CAN_MANAGE" to canManage.toString(),
                "NAME" to name,
                "EMAIL" to (user?.email ?: ""),
                "INITIALS" to initials,
                "PICTURE" to picture.orEmpty(),
                "AVATAR_IMAGE_HIDDEN" to if (picture == null) "hidden" else "",
                "AVATAR_INITIALS_HIDDEN" to if (picture == null) "" else "hidden",
                "MANAGEMENT_HIDDEN" to if (canManage) "" else "hidden",
                "CONTENT_HIDDEN" to if (page == null) "hidden" else "",
                "BLOCKED_HIDDEN" to if (page == null) "" else "hidden",
                "CONTENT_URL" to (page?.let { "/p/${it.id}/content" } ?: "about:blank"),
                "CSRF" to csrf,
                "DOWNLOAD_ID" to ArtifactViewerIds.DOWNLOAD,
                "SHARE_ID" to ArtifactViewerIds.SHARE,
                "AVATAR_ID" to ArtifactViewerIds.AVATAR,
                "FRAME_ID" to ArtifactViewerIds.FRAME,
                "INTERNAL_ID" to ArtifactViewerIds.INTERNAL,
                "PUBLIC_ID" to ArtifactViewerIds.PUBLIC,
                "COPY_ID" to ArtifactViewerIds.COPY,
                "URL_ID" to ArtifactViewerIds.URL,
                "SAVE_ID" to ArtifactViewerIds.SAVE,
                "CANCEL_ID" to ArtifactViewerIds.CANCEL,
                "CLOSE_ID" to ArtifactViewerIds.CLOSE,
            )
        return Regex("\\{\\{([A-Z_]+)}}").replace(template) { HtmlUtils.htmlEscape(values.getValue(it.groupValues[1])) }
    }
}
