package dev.uncomplex.dumphere.adapters.api

import org.springframework.mock.web.MockHttpServletRequest
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RedirectUrlSupportTests {
    @Test
    fun preservesLiteralPlusSignsAndEncodedValuesAcrossBothLoginRedirects() {
        val target = "/oauth2/authorize?scope=openid+email+profile&state=a%2Bb&redirect_uri=http%3A%2F%2Flocalhost%2Fcallback"
        for (url in listOf(RedirectUrlSupport.loginUrl(target), RedirectUrlSupport.googleAuthorizationUrl(target))) {
            val decoded = URLDecoder.decode(URI.create(url).rawQuery.substringAfter("redirectUrl="), Charsets.UTF_8)
            assertEquals(target, decoded)
            assertEquals(target, RedirectUrlSupport.sanitize(decoded))
        }
    }

    @Test
    fun storesRedirectUrlByStateIdInSession() {
        val request = MockHttpServletRequest()

        RedirectUrlSupport.remember(requireNotNull(request.session), "state-1", "/oauth2/consent?state=abc")

        assertEquals("/oauth2/consent?state=abc", RedirectUrlSupport.take(request.session, "state-1"))
        assertNull(RedirectUrlSupport.take(request.session, "state-1"))
    }

    @Test
    fun rejectsAbsoluteRedirectUrl() {
        assertNull(RedirectUrlSupport.sanitize("https://evil.example/phish"))
    }
}
