package beer.xiaoruru.article;

import org.springframework.web.util.HtmlUtils;

/** Builds a short, line-preserving text preview from the already sanitized article HTML. */
final class ArticleCardPreview {
    private static final int MAX_CHARACTERS = 1200;

    private ArticleCardPreview() {}

    static String fromRenderedHtml(String renderedHtml, String fallbackContent) {
        String source = renderedHtml == null || renderedHtml.isBlank() ? fallbackContent : renderedHtml;
        if (source == null || source.isBlank()) {
            return "";
        }

        String text = source
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(?:p|div|blockquote|h[1-6]|li|pre|table|tr|details)\\s*>", "\n")
                .replaceAll("(?s)<[^>]+>", "");
        text = HtmlUtils.htmlUnescape(text);

        String preview = text
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll("(?m)^ +| +$", "")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
        return preview.length() <= MAX_CHARACTERS ? preview : preview.substring(0, MAX_CHARACTERS).stripTrailing();
    }
}
