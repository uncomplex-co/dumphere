package dev.uncomplex.dumphere.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HtmlDocumentEditorTests {
    @Test
    fun replacesUniqueMatch() {
        val result = HtmlDocumentEditor.replace("<div>old</div>", "old", "new")

        assertEquals("<div>new</div>", result)
    }

    @Test
    fun replacesAllMatchesWhenRequested() {
        val result = HtmlDocumentEditor.replace("old old old", "old", "new", replaceAll = true)

        assertEquals("new new new", result)
    }

    @Test
    fun failsWhenMatchIsAmbiguous() {
        val error =
            assertFailsWith<IllegalStateException> {
                HtmlDocumentEditor.replace("old old", "old", "new")
            }

        assertEquals(
            "Found multiple matches for oldString. Provide more surrounding context to make the match unique.",
            error.message,
        )
    }

    @Test
    fun preservesWindowsLineEndings() {
        val result = HtmlDocumentEditor.applyWithOriginalLineEndings("<div>\r\nold\r\n</div>", "old", "new")

        assertEquals("<div>\r\nnew\r\n</div>", result)
    }
}
