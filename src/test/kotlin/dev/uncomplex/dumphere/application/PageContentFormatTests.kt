package dev.uncomplex.dumphere.application

import kotlin.test.Test
import kotlin.test.assertEquals

class PageContentFormatTests {
    @Test
    fun parsesHtmlToolType() {
        assertEquals(PageContentFormat.HTML, PageContentFormat.fromToolType("html"))
    }

    @Test
    fun parsesMarkdownToolType() {
        assertEquals(PageContentFormat.MARKDOWN, PageContentFormat.fromToolType("markdown"))
    }
}
