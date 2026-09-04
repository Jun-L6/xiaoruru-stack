package beer.xiaoruru.render;

import static org.assertj.core.api.Assertions.assertThat;

import beer.xiaoruru.article.ContentType;
import org.junit.jupiter.api.Test;

class ContentRendererTest {
    private final ContentRenderer renderer = new ContentRenderer();

    @Test
    void rendersTechnicalMarkdownAndKeepsMermaidMarker() {
        String markdown = """
                # 标题

                | 列一 | 列二 |
                | --- | --- |
                | A | B |

                - [x] 完成

                ```mermaid
                flowchart LR
                  A --> B
                ```
                """;

        String html = renderer.render(ContentType.MARKDOWN, markdown);

        assertThat(html).contains("<h1>", "<table>", "language-mermaid", "flowchart LR");
    }

    @Test
    void removesDangerousHtmlFromMarkdownAndHtml() {
        String dangerous = "<script>alert(1)</script><p onclick=\"alert(2)\">安全文字</p>"
                + "<a href=\"javascript:alert(3)\">链接</a>";

        String html = renderer.render(ContentType.HTML, dangerous);

        assertThat(html).contains("安全文字", "链接");
        assertThat(html).doesNotContain("script", "onclick", "javascript:");
    }

    @Test
    void escapesPlainText() {
        String html = renderer.render(ContentType.TEXT, "<script>not html</script>\nline 2");
        assertThat(html).contains("&lt;script&gt;not html&lt;/script&gt;", "line 2");
        assertThat(html).doesNotContain("<script>");
    }

    @Test
    void keepsSafeLocalMediaReferences() {
        String html = renderer.render(ContentType.MARKDOWN, "![图](/media/12/example.png)");
        assertThat(html).contains("src=\"/media/12/example.png\"");
    }

    @Test
    void keepsSafeExternalLinksButDropsUnsafeProtocols() {
        String html = renderer.render(ContentType.MARKDOWN,
                "[文档](https://example.com/docs) [危险](javascript:alert(1))");

        assertThat(html).contains("https://example.com/docs");
        assertThat(html).doesNotContain("javascript:");
    }
}
