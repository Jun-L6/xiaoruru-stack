package beer.xiaoruru.site;

import beer.xiaoruru.article.Article;
import beer.xiaoruru.article.ArticleKind;
import beer.xiaoruru.article.ArticleRepository;
import beer.xiaoruru.article.ArticleStatus;
import beer.xiaoruru.config.BlogProperties;
import beer.xiaoruru.render.ContentRenderer;
import beer.xiaoruru.setting.SiteSettingsService;
import beer.xiaoruru.taxonomy.Category;
import beer.xiaoruru.taxonomy.CategoryRepository;
import beer.xiaoruru.taxonomy.TagRepository;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
public class SiteController {
    private static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter RSS_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneId.of("GMT"));

    private final ArticleRepository articles;
    private final CategoryRepository categories;
    private final TagRepository tags;
    private final SiteSettingsService settings;
    private final ContentRenderer renderer;
    private final BlogProperties properties;

    public SiteController(ArticleRepository articles, CategoryRepository categories, TagRepository tags,
            SiteSettingsService settings, ContentRenderer renderer, BlogProperties properties) {
        this.articles = articles;
        this.categories = categories;
        this.tags = tags;
        this.settings = settings;
        this.renderer = renderer;
        this.properties = properties;
    }

    @GetMapping("/")
    public String home(@RequestParam(defaultValue = "0") int page, Model model) {
        Page<Article> result = articles.findByStatusAndKind(ArticleStatus.PUBLISHED, ArticleKind.POST,
                PageRequest.of(safePage(page), PAGE_SIZE, Sort.by(Sort.Order.desc("pinned"), Sort.Order.desc("publishedAt"))));
        model.addAttribute("page", result);
        model.addAttribute("pageTitle", settings.get("site.name", "小茹茹博客"));
        return "site/index";
    }

    @GetMapping("/posts/{slug}")
    public String article(@PathVariable String slug, Model model) {
        Article article = articles.findBySlugAndStatus(slug, ArticleStatus.PUBLISHED)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        model.addAttribute("article", article);
        model.addAttribute("pageTitle", article.getSeoTitle() == null ? article.getTitle() : article.getSeoTitle());
        model.addAttribute("description", article.getSeoDescription() == null ? article.getSummary() : article.getSeoDescription());
        model.addAttribute("canonical", normalizedPublicUrl() + "/posts/" + article.getSlug());
        model.addAttribute("previousArticle", articles
                .findFirstByStatusAndKindAndPublishedAtLessThanOrderByPublishedAtDesc(
                        ArticleStatus.PUBLISHED, ArticleKind.POST, article.getPublishedAt()).orElse(null));
        model.addAttribute("nextArticle", articles
                .findFirstByStatusAndKindAndPublishedAtGreaterThanOrderByPublishedAtAsc(
                        ArticleStatus.PUBLISHED, ArticleKind.POST, article.getPublishedAt()).orElse(null));
        return "site/article";
    }

    @GetMapping("/categories")
    public String categories(Model model) {
        List<CategoryGroup> groups = categoryGroups();
        Map<Long, Long> counts = new java.util.LinkedHashMap<>();
        for (CategoryGroup group : groups) {
            long rootCount = directPublishedCount(group.root());
            for (Category child : group.children()) {
                long childCount = directPublishedCount(child);
                counts.put(child.getId(), childCount);
                rootCount += childCount;
            }
            counts.put(group.root().getId(), rootCount);
        }
        model.addAttribute("categoryGroups", groups);
        model.addAttribute("categoryCounts", counts);
        model.addAttribute("pageTitle", "分类");
        return "site/categories";
    }

    @GetMapping("/categories/{slug}")
    public String category(@PathVariable String slug, @RequestParam(defaultValue = "0") int page, Model model) {
        Category category = categories.findBySlug(slug).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        model.addAttribute("heading", category.getPathName());
        model.addAttribute("description", category.getDescription());
        Page<Article> result = articles.findPublishedByCategorySlug(slug,
                PageRequest.of(safePage(page), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "publishedAt")));
        model.addAttribute("page", result);
        addPagination(model, result, "/categories/" + category.getSlug(), null);
        model.addAttribute("pageTitle", category.getName());
        return "site/article-list";
    }

    @GetMapping("/tags")
    public String tags(Model model) {
        var allTags = tags.findAllSorted();
        model.addAttribute("tags", allTags);
        model.addAttribute("tagCounts", allTags.stream().collect(Collectors.toMap(
                tag -> tag.getId(), tag -> articles.countByTagsIdAndStatusAndKind(
                        tag.getId(), ArticleStatus.PUBLISHED, ArticleKind.POST))));
        model.addAttribute("pageTitle", "标签");
        return "site/tags";
    }

    @GetMapping("/tags/{slug}")
    public String tag(@PathVariable String slug, @RequestParam(defaultValue = "0") int page, Model model) {
        var tag = tags.findBySlug(slug).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        model.addAttribute("heading", "标签：" + tag.getName());
        Page<Article> result = articles.findPublishedByTagSlug(slug,
                PageRequest.of(safePage(page), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "publishedAt")));
        model.addAttribute("page", result);
        addPagination(model, result, "/tags/" + tag.getSlug(), null);
        model.addAttribute("pageTitle", tag.getName());
        return "site/article-list";
    }

    @GetMapping("/archives")
    public String archives(Model model) {
        Map<Integer, List<Article>> archive = articles.findAllPublishedForArchive().stream()
                .collect(Collectors.groupingBy(a -> a.getPublishedAt().atZone(ZoneId.systemDefault()).getYear(),
                        java.util.LinkedHashMap::new, Collectors.toList()));
        model.addAttribute("archive", archive);
        model.addAttribute("pageTitle", "归档");
        return "site/archives";
    }

    @GetMapping("/search")
    public String search(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, Model model) {
        String query = q.strip();
        Page<Article> result = Page.empty();
        if (query.length() >= 2 && query.length() <= 100) {
            String databaseQuery = query.replace("\\", "").replace("%", "").replace("_", "").strip();
            if (databaseQuery.length() >= 2) {
                List<Article> ranked = articles.searchPublished(databaseQuery).stream()
                        .sorted(java.util.Comparator.comparingInt((Article article) -> searchRank(article, databaseQuery))
                                .thenComparing(Article::getPublishedAt, java.util.Comparator.reverseOrder()))
                        .toList();
                int requestedPage = safePage(page);
                int from = Math.min(requestedPage * PAGE_SIZE, ranked.size());
                int to = Math.min(from + PAGE_SIZE, ranked.size());
                result = new PageImpl<>(ranked.subList(from, to), PageRequest.of(requestedPage, PAGE_SIZE), ranked.size());
            }
        }
        model.addAttribute("heading", query.isBlank() ? "搜索" : "搜索：“" + query + "”");
        model.addAttribute("query", query);
        model.addAttribute("page", result);
        addPagination(model, result, "/search", query);
        model.addAttribute("pageTitle", "搜索");
        return "site/article-list";
    }

    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("aboutHtml", renderer.render(beer.xiaoruru.article.ContentType.MARKDOWN,
                settings.get("site.about", "# 关于")));
        model.addAttribute("pageTitle", "关于");
        return "site/about";
    }

    @GetMapping(value = "/feed.xml", produces = "application/rss+xml;charset=UTF-8")
    public ResponseEntity<String> feed() {
        List<Article> recent = articles.findByStatusAndKind(ArticleStatus.PUBLISHED, ArticleKind.POST,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "publishedAt"))).getContent();
        String siteName = escape(settings.get("site.name", "小茹茹博客"));
        String root = normalizedPublicUrl();
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>")
                .append("<title>").append(siteName).append("</title><link>").append(escape(root)).append("</link>")
                .append("<description>").append(escape(settings.get("site.subtitle", ""))).append("</description>");
        for (Article article : recent) {
            String url = root + "/posts/" + article.getSlug();
            xml.append("<item><title>").append(escape(article.getTitle())).append("</title>")
                    .append("<link>").append(escape(url)).append("</link><guid>").append(escape(url)).append("</guid>")
                    .append("<description>").append(escape(article.getSummary())).append("</description>")
                    .append("<pubDate>").append(RSS_DATE.format(article.getPublishedAt())).append("</pubDate></item>");
        }
        xml.append("</channel></rss>");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/rss+xml;charset=UTF-8")).body(xml.toString());
    }

    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    public ResponseEntity<String> sitemap() {
        String root = normalizedPublicUrl();
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        for (String path : List.of("/", "/archives", "/categories", "/tags", "/about")) {
            xml.append("<url><loc>").append(escape(root + path)).append("</loc></url>");
        }
        for (Article article : articles.findAllPublishedForArchive()) {
            xml.append("<url><loc>").append(escape(root + "/posts/" + article.getSlug())).append("</loc>")
                    .append("<lastmod>").append(article.getUpdatedAt()).append("</lastmod></url>");
        }
        xml.append("</urlset>");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/xml;charset=UTF-8")).body(xml.toString());
    }

    private List<CategoryGroup> categoryGroups() {
        List<Category> all = categories.findByEnabledTrueOrderBySortOrderAscNameAsc();
        return all.stream().filter(category -> category.getParent() == null)
                .map(root -> new CategoryGroup(root, all.stream()
                        .filter(child -> child.getParent() != null && child.getParent().getId().equals(root.getId()))
                        .toList()))
                .toList();
    }

    private int safePage(int page) {
        return Math.max(0, Math.min(page, 10_000));
    }

    private long directPublishedCount(Category category) {
        return articles.countByCategoryIdAndStatusAndKind(category.getId(), ArticleStatus.PUBLISHED, ArticleKind.POST);
    }

    private int searchRank(Article article, String query) {
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        if (contains(article.getTitle(), needle)) return 0;
        if (article.getTags().stream().anyMatch(tag -> contains(tag.getName(), needle))) return 1;
        if (contains(article.getSummary(), needle) || contains(article.getCategory().getName(), needle)) return 2;
        return 3;
    }

    private boolean contains(String value, String lowerNeedle) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(lowerNeedle);
    }

    private void addPagination(Model model, Page<Article> page, String path, String query) {
        if (page.hasPrevious()) {
            model.addAttribute("previousUrl", pageUrl(path, page.getNumber() - 1, query));
        }
        if (page.hasNext()) {
            model.addAttribute("nextUrl", pageUrl(path, page.getNumber() + 1, query));
        }
    }

    private String pageUrl(String path, int page, String query) {
        var builder = org.springframework.web.util.UriComponentsBuilder.fromPath(path).queryParam("page", page);
        if (query != null && !query.isBlank()) builder.queryParam("q", query);
        return builder.build().encode().toUriString();
    }

    private String normalizedPublicUrl() {
        return properties.publicUrl().replaceAll("/+$", "");
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
