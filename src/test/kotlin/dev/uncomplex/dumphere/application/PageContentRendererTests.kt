package dev.uncomplex.dumphere.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageContentRendererTests {
    private val renderer = PageContentRenderer()

    @Test
    fun returnsHtmlUnchanged() {
        val html = "<div>Hello</div>"

        assertEquals(html, renderer.render(html, PageContentFormat.HTML, "Ignored"))
    }

    @Test
    fun rendersMarkdownIntoHtmlDocument() {
        val rendered = renderer.render("# Hello", PageContentFormat.MARKDOWN, "Doc")

        assertTrue(rendered.contains("<title>Doc</title>"))
        assertTrue(rendered.contains("<h1>Hello</h1>"))
        assertTrue(rendered.contains("<!doctype html>"))
    }
}
