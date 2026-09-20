package beer.xiaoruru.site;

import beer.xiaoruru.article.Article;
import beer.xiaoruru.config.BlogProperties;
import beer.xiaoruru.setting.SiteSettingsService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/** Builds canonical URLs and safe Schema.org JSON-LD for public pages. */
@Service
public class SeoService {
    private final ObjectMapper objectMapper;
    private final SiteSettingsService settings;
    private final BlogProperties properties;

    public SeoService(ObjectMapper objectMapper, SiteSettingsService settings, BlogProperties properties) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.properties = properties;
    }

    public String rootUrl() {
        return properties.publicUrl().replaceAll("/+$", "");
    }

    public String canonical(String path) {
        String normalizedPath = path == null || path.isBlank() ? "/" : path;
        return rootUrl() + (normalizedPath.startsWith("/") ? normalizedPath : "/" + normalizedPath);
    }

    public String pagedCanonical(String path, int page) {
        if (page <= 0) return canonical(path);
        return UriComponentsBuilder.fromUriString(canonical(path)).queryParam("page", page)
                .build().encode().toUriString();
    }

    public String authorName() {
        String author = settings.get("site.author", "").strip();
        return author.isBlank() ? settings.get("site.name", "小茹茹博客") : author;
    }

    public String homeStructuredData() {
        ObjectNode document = baseDocument();
        ArrayNode graph = document.putArray("@graph");
        graph.add(authorNode());

        ObjectNode website = graph.addObject();
        website.put("@type", "WebSite");
        website.put("@id", canonical("/") + "#website");
        website.put("url", canonical("/"));
        website.put("name", settings.get("site.name", "小茹茹博客"));
        website.put("description", settings.get("site.subtitle", ""));
        website.put("inLanguage", "zh-CN");
        website.set("author", reference(authorId()));

        ObjectNode blog = graph.addObject();
        populateBlog(blog);
        return serialize(document);
    }

    public String articleStructuredData(Article article) {
        String articleUrl = canonical("/posts/" + article.getSlug());
        ObjectNode document = baseDocument();
        ArrayNode graph = document.putArray("@graph");
        graph.add(authorNode());
        populateBlog(graph.addObject());

        ObjectNode post = graph.addObject();
        post.put("@type", "BlogPosting");
        post.put("@id", articleUrl + "#article");
        post.put("url", articleUrl);
        post.put("mainEntityOfPage", articleUrl);
        post.put("headline", article.getTitle());
        if (article.getSummary() != null && !article.getSummary().isBlank()) {
            post.put("description", article.getSummary());
        }
        post.put("inLanguage", "zh-CN");
        post.put("datePublished", article.getPublishedAt().toString());
        post.put("dateModified", article.getUpdatedAt().toString());
        post.put("articleSection", article.getCategory().getPathName());
        String keywords = article.getTags().stream().map(tag -> tag.getName()).collect(Collectors.joining(", "));
        if (!keywords.isBlank()) post.put("keywords", keywords);
        post.set("author", reference(authorId()));
        post.set("publisher", reference(authorId()));
        post.set("isPartOf", reference(canonical("/") + "#blog"));

        ObjectNode breadcrumbs = graph.addObject();
        breadcrumbs.put("@type", "BreadcrumbList");
        breadcrumbs.put("@id", articleUrl + "#breadcrumb");
        ArrayNode items = breadcrumbs.putArray("itemListElement");
        addBreadcrumb(items, 1, "首页", canonical("/"));
        addBreadcrumb(items, 2, article.getCategory().getPathName(),
                canonical("/categories/" + article.getCategory().getSlug()));
        addBreadcrumb(items, 3, article.getTitle(), articleUrl);
        return serialize(document);
    }

    private ObjectNode baseDocument() {
        ObjectNode document = objectMapper.createObjectNode();
        document.put("@context", "https://schema.org");
        return document;
    }

    private ObjectNode authorNode() {
        ObjectNode author = objectMapper.createObjectNode();
        author.put("@type", "Person");
        author.put("@id", authorId());
        author.put("name", authorName());
        author.put("url", authorUrl());
        String biography = settings.get("site.author-bio", "").strip();
        if (!biography.isBlank()) author.put("description", biography);
        return author;
    }

    private void populateBlog(ObjectNode blog) {
        blog.put("@type", "Blog");
        blog.put("@id", canonical("/") + "#blog");
        blog.put("url", canonical("/"));
        blog.put("name", settings.get("site.name", "小茹茹博客"));
        blog.put("description", settings.get("site.subtitle", ""));
        blog.put("inLanguage", "zh-CN");
        blog.set("author", reference(authorId()));
    }

    private ObjectNode reference(String id) {
        ObjectNode reference = objectMapper.createObjectNode();
        reference.put("@id", id);
        return reference;
    }

    private void addBreadcrumb(ArrayNode items, int position, String name, String url) {
        ObjectNode item = items.addObject();
        item.put("@type", "ListItem");
        item.put("position", position);
        item.put("name", name);
        item.put("item", url);
    }

    private String authorId() {
        return authorUrl() + "#author";
    }

    private String authorUrl() {
        String configured = settings.get("site.author-url", "").strip();
        if (!configured.isBlank()) {
            try {
                URI uri = URI.create(configured);
                if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                    return configured;
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid public settings fall back to the local author page.
            }
        }
        return canonical("/about");
    }

    private String serialize(ObjectNode document) {
        try {
            return objectMapper.writeValueAsString(document)
                    .replace("&", "\\u0026")
                    .replace("<", "\\u003c")
                    .replace(">", "\\u003e")
                    .replace("\u2028", "\\u2028")
                    .replace("\u2029", "\\u2029");
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize structured data", exception);
        }
    }
}
