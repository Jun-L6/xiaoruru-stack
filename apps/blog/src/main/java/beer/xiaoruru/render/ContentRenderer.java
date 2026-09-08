package beer.xiaoruru.render;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.footnotes.FootnoteExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import beer.xiaoruru.article.ContentType;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/**
 * 正文渲染与安全过滤边界。
 *
 * <p>Markdown 和 HTML 最终都必须经过同一份白名单；TEXT 始终作为纯文本转义。
 * 数据库中的 {@code rendered_html} 只存放此类产生的安全快照。
 */
@Service
public class ContentRenderer {
    /** 渲染规则变更时递增，便于识别需要重新渲染的文章。 */
    public static final int RENDER_VERSION = 1;
    private static final Pattern SAFE_CLASS = Pattern.compile("[a-zA-Z0-9_ -]{1,160}");
    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9_:-]{1,160}");

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final PolicyFactory sanitizer;

    public ContentRenderer() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListExtension.create(),
                FootnoteExtension.create(),
                AutolinkExtension.create()
        ));
        options.set(HtmlRenderer.SOFT_BREAK, "\n");
        parser = Parser.builder(options).build();
        renderer = HtmlRenderer.builder(options).build();

        PolicyFactory articlePolicy = new HtmlPolicyBuilder()
                .allowElements("h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "span",
                        "pre", "code", "blockquote", "ul", "ol", "li", "hr", "br", "strong",
                        "em", "del", "s", "sup", "sub", "details", "summary", "kbd", "mark",
                        "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "a", "img")
                .allowAttributes("class").matching(SAFE_CLASS).globally()
                .allowAttributes("id").matching(SAFE_ID).onElements("h1", "h2", "h3", "h4", "h5", "h6", "div", "span")
                .allowAttributes("title").globally()
                .allowAttributes("href").onElements("a")
                .allowAttributes("src", "alt", "width", "height", "loading").onElements("img")
                .allowAttributes("colspan", "rowspan", "scope").onElements("th", "td")
                .allowUrlProtocols("http", "https")
                .requireRelNofollowOnLinks()
                .toFactory();
        sanitizer = Sanitizers.BLOCKS
                .and(Sanitizers.FORMATTING)
                .and(Sanitizers.TABLES)
                .and(Sanitizers.LINKS)
                .and(Sanitizers.IMAGES)
                .and(articlePolicy);
    }

    public String render(ContentType type, String source) {
        String safeSource = source == null ? "" : source;
        return switch (type) {
            case MARKDOWN -> sanitize(renderer.render(parser.parse(safeSource)));
            case HTML -> sanitize(safeSource);
            case TEXT -> "<div class=\"plain-text\">" + HtmlUtils.htmlEscape(safeSource) + "</div>";
        };
    }

    public String sanitize(String html) {
        return sanitizer.sanitize(html == null ? "" : html);
    }

    /** 为搜索、自动标题、摘要和 AI 输入生成紧凑纯文本，不用于前台渲染。 */
    public String toPlainText(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        String withoutFences = source.replaceAll("(?s)```.*?```", " ");
        String withoutTags = withoutFences.replaceAll("<[^>]+>", " ");
        return HtmlUtils.htmlUnescape(withoutTags)
                .replaceAll("[#*_>`~\\[\\]{}|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
