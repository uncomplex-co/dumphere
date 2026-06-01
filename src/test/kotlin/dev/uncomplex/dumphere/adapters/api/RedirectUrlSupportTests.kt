package dev.uncomplex.dumphere.adapters.api

import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class RedirectUrlSupportTests {
    @Test
    fun buildsLoginUrlWithEncodedRedirectUrl() {
        val loginUrl = RedirectUrlSupport.loginUrl("/oauth2/authorize?client_id=dumphere&state=abc")

        assertTrue(loginUrl.startsWith("/login?redirectUrl="))
        assertTrue(loginUrl.contains("oauth2"))
        assertTrue(loginUrl.contains("client_id"))
        assertTrue(loginUrl.contains("state"))
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
