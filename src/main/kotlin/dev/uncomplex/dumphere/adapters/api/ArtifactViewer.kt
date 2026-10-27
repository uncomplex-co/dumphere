package dev.uncomplex.dumphere.adapters.api

import dev.uncomplex.dumphere.application.PageContentFormat
import dev.uncomplex.dumphere.application.PublishedPage
import dev.uncomplex.dumphere.application.authenticatedUser
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.thymeleaf.ITemplateEngine
import org.thymeleaf.context.Context

object ArtifactViewerIds {
    const val DOWNLOAD = "artifact-download-button"
    const val SHARE = "artifact-share-button"
    const val AVATAR = "account-avatar"
    const val FRAME = "artifact-content-frame"
    const val DOCUMENT_FRAME = "artifact-document-frame"
    const val INTERNAL = "share-internal-radio"
    const val PUBLIC = "share-public-radio"
    const val COPY = "share-copy-link-button"
    const val URL = "share-url-input"
    const val SAVE = "share-save-button"
    const val CANCEL = "share-cancel-button"
    const val CLOSE = "share-close-button"
    const val COMMENTS = "artifact-comments-button"
    const val COMMENTS_CLOSE = "comments-close-button"
    const val COMMENT_ADD = "comments-add-button"
    const val ELEMENT_SELECTION = "comments-selection-toggle"
    const val COMMENT_BODY = "comments-body-input"
    const val COMMENT_POST = "comments-post-button"
    const val COMMENT_CANCEL = "comments-cancel-button"
    const val COMMENT_RETRY = "comments-retry-button"
    const val THREAD_PIN = "comments-thread-pin"
    const val COMMENT = "artifact-comment"
    const val THREAD = "artifact-comment-thread"
    const val THREAD_REPLY = "comments-reply-button"
    const val START_DISCUSSION = "discussion-add-comment-button"
    const val TITLE_MENU = "artifact-title-menu-button"
    const val TITLE_SHARE = "artifact-menu-share-button"
    const val TITLE_RELOAD = "artifact-menu-reload-button"
    const val ARTIFACTS = "artifact-library-button"
    const val ARTIFACTS_CLOSE = "artifact-library-close-button"
    const val ARTIFACTS_RETRY = "artifact-library-retry-button"
    const val ARTIFACT_LINK = "artifact-library-link"
}

@Component
class ArtifactViewer(
    private val templates: ITemplateEngine,
) {
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
                "CAN_VIEW" to (page != null),
                "CAN_MANAGE" to canManage,
                "NAME" to name,
                "EMAIL" to (user?.email ?: ""),
                "INITIALS" to initials,
                "PICTURE" to picture.orEmpty(),
                "HAS_PICTURE" to (picture != null),
                "WRAPPER_URL" to (page?.let { "/p/${it.id}/wrapper" } ?: "about:blank"),
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
                "COMMENTS_ID" to ArtifactViewerIds.COMMENTS,
                "COMMENTS_CLOSE_ID" to ArtifactViewerIds.COMMENTS_CLOSE,
                "COMMENT_ADD_ID" to ArtifactViewerIds.COMMENT_ADD,
                "ELEMENT_SELECTION_ID" to ArtifactViewerIds.ELEMENT_SELECTION,
                "COMMENT_BODY_ID" to ArtifactViewerIds.COMMENT_BODY,
                "COMMENT_POST_ID" to ArtifactViewerIds.COMMENT_POST,
                "COMMENT_CANCEL_ID" to ArtifactViewerIds.COMMENT_CANCEL,
                "COMMENT_RETRY_ID" to ArtifactViewerIds.COMMENT_RETRY,
                "THREAD_PIN_ID" to ArtifactViewerIds.THREAD_PIN,
                "COMMENT_ID" to ArtifactViewerIds.COMMENT,
                "THREAD_ID" to ArtifactViewerIds.THREAD,
                "THREAD_REPLY_ID" to ArtifactViewerIds.THREAD_REPLY,
                "START_DISCUSSION_ID" to ArtifactViewerIds.START_DISCUSSION,
                "TITLE_MENU_ID" to ArtifactViewerIds.TITLE_MENU,
                "TITLE_SHARE_ID" to ArtifactViewerIds.TITLE_SHARE,
                "TITLE_RELOAD_ID" to ArtifactViewerIds.TITLE_RELOAD,
                "ARTIFACTS_ID" to ArtifactViewerIds.ARTIFACTS,
                "ARTIFACTS_CLOSE_ID" to ArtifactViewerIds.ARTIFACTS_CLOSE,
                "ARTIFACTS_RETRY_ID" to ArtifactViewerIds.ARTIFACTS_RETRY,
                "ARTIFACT_LINK_ID" to ArtifactViewerIds.ARTIFACT_LINK,
            )
        val context = Context().apply { setVariables(values) }
        return templates.process("artifact-viewer", context)
    }

    fun renderWrapper(page: PublishedPage): String {
        val context =
            Context().apply {
                setVariables(
                    mapOf(
                        "TITLE" to page.title,
                        "CONTENT_URL" to "/p/${page.id}/content",
                        "DOCUMENT_FRAME_ID" to ArtifactViewerIds.DOCUMENT_FRAME,
                        "THREAD_PIN_ID" to ArtifactViewerIds.THREAD_PIN,
                    ),
                )
            }
        return templates.process("artifact-wrapper", context)
    }
}
