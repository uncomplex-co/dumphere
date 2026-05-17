package dev.uncomplex.dumphere.application

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.stereotype.Service
import org.springframework.web.util.HtmlUtils

@Service
class PageContentRenderer {
    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
        YamlFrontMatterExtension.create(),
        HeadingAnchorExtension.create(),
        FootnotesExtension.create(),
        AutolinkExtension.create(),
    )

    private val markdownParser = Parser.builder().extensions(extensions).build()
    private val markdownRenderer =
        HtmlRenderer
            .builder()
            .extensions(extensions)
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
                input[type="checkbox"] {
                  margin-right: 8px;
                  vertical-align: middle;
                }
                li.task-list-item {
                  list-style: none;
                }
                .footnote {
                  font-size: 0.85em;
                  color: var(--muted);
                }
                .footnote-ref {
                  text-decoration: none;
                  font-size: 0.85em;
                  vertical-align: super;
                }
                .footnotes {
                  margin-top: 40px;
                  padding-top: 16px;
                  border-top: 1px solid var(--border);
                }
                .footnotes ol {
                  padding-left: 20px;
                }
                .footnotes li {
                  font-size: 0.9em;
                  color: var(--muted);
                }
                h1, h2, h3, h4, h5, h6 {
                  position: relative;
                }
                h1:hover .anchor, h2:hover .anchor, h3:hover .anchor, h4:hover .anchor, h5:hover .anchor, h6:hover .anchor {
                  opacity: 1;
                }
                .anchor {
                  position: absolute;
                  left: -24px;
                  top: 0;
                  opacity: 0;
                  text-decoration: none;
                  color: var(--muted);
                  font-size: 0.8em;
                  transition: opacity 0.1s;
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
