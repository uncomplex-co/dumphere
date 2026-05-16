package dev.uncomplex.dumphere.application

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.stereotype.Service
import org.springframework.web.util.HtmlUtils

@Service
class PageContentRenderer {
    private val markdownParser = Parser.builder().build()
    private val markdownRenderer =
        HtmlRenderer
            .builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build()

    fun render(
        contents: String,
        format: PageContentFormat,
        title: String,
    ): String =
        when (format) {
            PageContentFormat.HTML -> contents
            PageContentFormat.MARKDOWN -> renderMarkdown(contents, title)
        }

    private fun renderMarkdown(
        markdown: String,
        title: String,
    ): String {
        val rendered = markdownRenderer.render(markdownParser.parse(markdown))
        val escapedTitle = HtmlUtils.htmlEscape(title)

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>$escapedTitle</title>
              <style>
                :root {
                  color-scheme: light dark;
                  --bg: #ffffff;
                  --fg: #1f2328;
                  --muted: #59636e;
                  --border: #d0d7de;
                  --code-bg: #f6f8fa;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  background: var(--bg);
                  color: var(--fg);
                  font: 16px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                }
                main {
                  width: min(900px, calc(100% - 32px));
                  margin: 40px auto;
                }
                img { max-width: 100%; }
                pre, code {
                  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
                }
                code {
                  background: var(--code-bg);
                  padding: 0.15em 0.35em;
                  border-radius: 6px;
                }
                pre {
                  background: var(--code-bg);
                  padding: 16px;
                  overflow-x: auto;
                  border-radius: 10px;
                }
                pre code {
                  background: transparent;
                  padding: 0;
                }
                table {
                  border-collapse: collapse;
                  width: 100%;
                }
                th, td {
                  border: 1px solid var(--border);
                  padding: 8px 12px;
                  text-align: left;
                }
                blockquote {
                  margin: 0;
                  padding-left: 16px;
                  border-left: 4px solid var(--border);
                  color: var(--muted);
                }
              </style>
            </head>
            <body>
              <main>
                $rendered
              </main>
            </body>
            </html>
            """.trimIndent()
    }
}
