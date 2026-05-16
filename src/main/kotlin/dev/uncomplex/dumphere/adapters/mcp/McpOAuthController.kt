package dev.uncomplex.dumphere.adapters.mcp

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController

@RestController
class McpOAuthController(
    private val mcpOAuthService: McpOAuthService,
) {
    @GetMapping("/oauth2/authorize")
    fun authorize(
        @RequestParam("response_type", required = false) responseType: String?,
        @RequestParam("client_id", required = false) clientId: String?,
        @RequestParam("redirect_uri", required = false) redirectUri: String?,
        @RequestParam("state", required = false) state: String?,
        @RequestParam("code_challenge", required = false) codeChallenge: String?,
        @RequestParam("code_challenge_method", required = false) codeChallengeMethod: String?,
        @RequestParam("scope", required = false) scope: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        try {
            val authorizationRequest =
                mcpOAuthService.validateAuthorizationRequest(
                    responseType = responseType,
                    clientId = clientId,
                    redirectUri = redirectUri,
                    state = state,
                    codeChallenge = codeChallenge,
                    codeChallengeMethod = codeChallengeMethod,
                    scope = scope,
                )
            request.session.setAttribute(McpAuthorizationRequest.sessionKey(authorizationRequest.state), authorizationRequest)
            response.sendRedirect("/oauth2/consent?state=${authorizationRequest.state}")
        } catch (error: OAuthRequestException) {
            if (error.redirectUri != null && !state.isNullOrBlank() && !clientId.isNullOrBlank()) {
                val fallback =
                    McpAuthorizationRequest(
                        clientId = clientId,
                        clientName = "this MCP client",
                        redirectUri = error.redirectUri,
                        state = state,
                        codeChallenge = codeChallenge.orEmpty(),
                        requestedScopes = emptySet(),
                    )
                response.sendRedirect(mcpOAuthService.authorizationErrorRedirect(fallback, error.error))
            } else {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, error.message)
            }
        }
    }

    @PostMapping("/oauth2/token", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun token(
        @RequestParam("grant_type", required = false) grantType: String?,
        @RequestParam("code", required = false) code: String?,
        @RequestParam("redirect_uri", required = false) redirectUri: String?,
        @RequestParam("code_verifier", required = false) codeVerifier: String?,
        @RequestParam("client_id", required = false) clientId: String?,
        @RequestParam("client_secret", required = false) clientSecret: String?,
        @RequestHeader("Authorization", required = false) authorizationHeader: String?,
    ): ResponseEntity<Map<String, Any>> {
        if (grantType != "authorization_code") {
            return ResponseEntity.badRequest().body(mapOf<String, Any>("error" to "unsupported_grant_type"))
        }

        return try {
            val token =
                mcpOAuthService.exchangeAuthorizationCode(
                    code = code,
                    redirectUri = redirectUri,
                    codeVerifier = codeVerifier,
                    authorizationHeader = authorizationHeader,
                    clientIdParam = clientId,
                    clientSecretParam = clientSecret,
                )
            ResponseEntity.ok(
                mapOf<String, Any>(
                    "access_token" to token.accessToken,
                    "token_type" to "Bearer",
                    "expires_in" to token.expiresIn,
                ),
            )
        } catch (error: OAuthTokenException) {
            val response = mcpOAuthService.tokenErrorResponse(error)
            ResponseEntity.status(response.statusCode).headers(response.headers).body(response.body ?: emptyMap())
        }
    }
}
