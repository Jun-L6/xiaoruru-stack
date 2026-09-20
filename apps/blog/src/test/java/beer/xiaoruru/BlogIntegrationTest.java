package beer.xiaoruru;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import beer.xiaoruru.article.Article;
import beer.xiaoruru.article.ArticleCommand;
import beer.xiaoruru.article.ArticleService;
import beer.xiaoruru.article.ArticleRepository;
import beer.xiaoruru.article.ContentType;
import beer.xiaoruru.article.ContentForm;
import beer.xiaoruru.article.SummaryOrigin;
import beer.xiaoruru.article.TitleOrigin;
import beer.xiaoruru.taxonomy.Category;
import beer.xiaoruru.taxonomy.CategoryRepository;
import beer.xiaoruru.taxonomy.TagRepository;
import beer.xiaoruru.taxonomy.TaxonomyService;
import beer.xiaoruru.media.MediaService;
import beer.xiaoruru.setting.SiteSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.util.Base64;
import java.util.Map;

@SpringBootTest
@AutoConfigureMockMvc
class BlogIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ArticleService articleService;
    @Autowired ArticleRepository articles;
    @Autowired CategoryRepository categories;
    @Autowired TagRepository tags;
    @Autowired TaxonomyService taxonomy;
    @Autowired MediaService media;
    @Autowired SiteSettingsService settings;

    @Test
    void publicPagesAreAvailableAndAdminIsProtected() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("site/index"));
        mvc.perform(get("/categories"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("技术与创造")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("生活与见闻")));
        mvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void rssLandingPageGuidesReadersWhileFeedRemainsMachineReadable() throws Exception {
        mvc.perform(get("/rss"))
                .andExpect(status().isOk())
                .andExpect(view().name("site/rss"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<link rel=\"canonical\" href=\"http://localhost/rss\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "value=\"http://localhost/feed.xml\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-copy-rss")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("直接查看 RSS Feed")));

        mvc.perform(get("/"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/rss\">RSS 订阅")));
        mvc.perform(get("/feed.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/rss+xml"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<rss version=\"2.0\">")));
        mvc.perform(get("/sitemap.xml"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("http://localhost/rss")));
    }

    @Test
    void savesPublishesAndDisplaysMarkdownArticle() throws Exception {
        Long categoryId = categories.findBySlug("software-development").orElseThrow().getId();
        Article article = articleService.save(new ArticleCommand(null, "JVM 字节码入门", "jvm-bytecode-test", "",
                ContentType.MARKDOWN, ContentForm.LONGFORM, "# JVM\n\n```java\nclass Demo {}\n```", categoryId,
                "Java, JVM", "", "", false, true, "", ""));
        articleService.publish(article.getId());

        mvc.perform(get("/posts/jvm-bytecode-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("JVM 字节码入门")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("language-java")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-mermaid-src=")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<script src=\"/webjars/mermaid"))));
    }

    @Test
    void versionedWebjarsUseLongLivedBrowserCaching() throws Exception {
        mvc.perform(get("/webjars/mermaid/11.17.1/dist/mermaid.min.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("max-age=31536000")))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("immutable")));
    }

    @Test
    void readingStyleSettingsAreValidatedAndRenderedOnArticles() throws Exception {
        mvc.perform(post("/admin/settings").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("site.article-width", "wide")
                        .param("site.article-font-size", "large")
                        .param("site.article-line-height", "compact")
                        .param("site.code-theme", "paper")
                        .param("site.toc-mode", "floating"))
                .andExpect(status().is3xxRedirection());

        Long categoryId = categories.findBySlug("software-development").orElseThrow().getId();
        Article article = articleService.save(new ArticleCommand(null, "阅读样式", "reading-style-test", "",
                ContentType.MARKDOWN, ContentForm.LONGFORM, "## 第一节\n\n内容\n\n## 第二节\n\n内容", categoryId,
                "", "", "", false, true, "", ""));
        articleService.publish(article.getId());

        mvc.perform(get("/posts/reading-style-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-reading-width=\"wide\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-reading-size=\"large\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-reading-leading=\"compact\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-code-theme=\"paper\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-article-toc")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-state=\"loading\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("正在整理目录")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("data-article-toc hidden"))));

        mvc.perform(post("/admin/settings").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("site.article-width", "unsafe-value")
                        .param("site.article-font-size", "unsafe-value")
                        .param("site.article-line-height", "unsafe-value")
                        .param("site.code-theme", "unsafe-value")
                        .param("site.toc-mode", "unsafe-value"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/posts/reading-style-test"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-reading-width=\"standard\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-reading-size=\"standard\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-reading-leading=\"standard\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-code-theme=\"soft\"")));
    }

    @Test
    void authenticatedAdminCanOpenEditorAndCsrfProtectsWrites() throws Exception {
        mvc.perform(get("/admin/articles/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/article-edit"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AI 自动判断")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("content-form-field")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("content-field-label")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-file-picker")));
        mvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("admin/dashboard"));
        mvc.perform(get("/admin/articles").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("admin/articles"));
        mvc.perform(get("/admin/categories").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("admin/categories"));
        mvc.perform(get("/admin/tags").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("admin/tags"));
        mvc.perform(get("/admin/media").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-file-picker")));

        mvc.perform(post("/admin/settings").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/admin/settings").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("site.name", "测试博客"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/settings"));
    }

    @Test
    void existingArticleEditorLoadsDetailedRelationships() throws Exception {
        Long categoryId = categories.findBySlug("software-development").orElseThrow().getId();
        Article article = articleService.save(new ArticleCommand(null, "编辑关系测试", "editor-relations-test", "",
                ContentType.MARKDOWN, ContentForm.NOTE, "# 内容", categoryId, "C++, ABI", "", "",
                false, true, "", ""));

        mvc.perform(get("/admin/articles/" + article.getId()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/article-edit"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("C++")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ABI")));
    }

    @Test
    void manualCategoryOrContentFormOnNewArticleLocksAiClassification() {
        Long categoryId = categories.findBySlug("daily-moments").orElseThrow().getId();
        Article categorized = articleService.save(new ArticleCommand(null, "周末小记", "weekend-note-test", "",
                ContentType.TEXT, ContentForm.LONGFORM, "今天去公园散步。", categoryId,
                "", "", "", false, false, "", ""));
        Long uncategorizedId = categories.findBySlug("uncategorized").orElseThrow().getId();
        Article shaped = articleService.save(new ArticleCommand(null, "一瞬", "moment-form-test", "",
                ContentType.TEXT, ContentForm.MOMENT, "风吹过。", uncategorizedId,
                "", "", "", false, false, "", ""));

        assertThat(categorized.isClassificationLocked()).isTrue();
        assertThat(categorized.getClassificationSource().name()).isEqualTo("MANUAL");
        assertThat(shaped.isClassificationLocked()).isTrue();
        assertThat(shaped.getClassificationSource().name()).isEqualTo("MANUAL");
    }

    @Test
    void automaticContentFormStaysUnlockedAndCanBeRestoredAfterManualChoice() {
        Long uncategorizedId = categories.findBySlug("uncategorized").orElseThrow().getId();
        Article automatic = articleService.save(new ArticleCommand(null, "", "auto-form-interaction-test", "",
                ContentType.TEXT, null, "风从窗口路过。", uncategorizedId,
                "", "", "", false, false, "", ""));

        assertThat(automatic.isContentFormAutomatic()).isTrue();
        assertThat(automatic.isClassificationLocked()).isFalse();
        assertThat(automatic.getTitleOrigin()).isEqualTo(TitleOrigin.GENERATED);

        Article manual = articleService.save(new ArticleCommand(automatic.getId(), "", automatic.getSlug(),
                automatic.getSummary(), ContentType.TEXT, ContentForm.MOMENT, automatic.getContent(),
                uncategorizedId, "", "", "", false, false, "", ""));
        assertThat(manual.isContentFormAutomatic()).isFalse();
        assertThat(manual.isClassificationLocked()).isTrue();

        Article restored = articleService.save(new ArticleCommand(manual.getId(), "", manual.getSlug(),
                manual.getSummary(), ContentType.TEXT, null, manual.getContent(), uncategorizedId,
                "", "", "", false, true, "", ""));
        assertThat(restored.isContentFormAutomatic()).isTrue();
        assertThat(restored.isClassificationLocked()).isFalse();
    }

    @Test
    void titlelessMomentRendersItsContentOnceWithoutHeadingOrLead() throws Exception {
        Long uncategorizedId = categories.findBySlug("uncategorized").orElseThrow().getId();
        String sentence = "纸上得来终觉浅，绝知此事要躬行。";
        Article moment = articleService.save(new ArticleCommand(null, "", "titleless-moment-test", "",
                ContentType.TEXT, ContentForm.MOMENT, sentence, uncategorizedId,
                "实践", "", "", false, false, "", ""));
        articleService.publish(moment.getId());

        assertThat(moment.getTitleOrigin()).isEqualTo(TitleOrigin.GENERATED);
        assertThat(moment.getSummaryOrigin()).isEqualTo(SummaryOrigin.GENERATED);
        assertThat(moment.isTitleDisplayed()).isFalse();
        String detail = mvc.perform(get("/posts/titleless-moment-test"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String articleMain = detail.substring(detail.indexOf("<main class=\"article-shell"), detail.indexOf("</main>"));
        String articleContent = articleMain.substring(articleMain.indexOf("<div class=\"article-content"),
                articleMain.indexOf("</div>", articleMain.indexOf("<div class=\"article-content")));
        assertThat(articleMain).contains("<h1 class=\"sr-only\"").doesNotContain("article-lead");
        assertThat(org.springframework.util.StringUtils.countOccurrencesOf(articleContent, sentence)).isEqualTo(1);

        String home = mvc.perform(get("/")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(home).contains("form-moment", "moment-card-copy", "titleless-moment-test");
    }

    @Test
    void compactCardsShowOriginalContentInsteadOfGeneratedOrManualSummary() throws Exception {
        Long categoryId = categories.findBySlug("snippets-poetry").orElseThrow().getId();
        String poem = "年岁渐长\n悲喜渐淡\n浮名如梦\n\n安守寻常，自得宽心。";
        Article moment = articleService.save(new ArticleCommand(null, "", "poem-card-preview-test",
                "这段概括摘要不应该出现在动态卡片中", ContentType.TEXT, ContentForm.MOMENT, poem, categoryId,
                "短诗, 心境", "", "", false, false, "", ""));
        articleService.publish(moment.getId());

        Article excerpt = articleService.save(new ArticleCommand(null, "", "excerpt-card-preview-test",
                "这段概括摘要不应该出现在摘录卡片中", ContentType.TEXT, ContentForm.EXCERPT,
                "真正的平静，\n是在心中修篱种菊。", categoryId,
                "摘录", "某书", "", false, false, "", ""));
        articleService.publish(excerpt.getId());

        String home = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(home)
                .contains("年岁渐长\n悲喜渐淡\n浮名如梦", "真正的平静，\n是在心中修篱种菊。")
                .doesNotContain("这段概括摘要不应该出现在动态卡片中",
                        "这段概括摘要不应该出现在摘录卡片中");
    }

    @Test
    void excerptSupportsOptionalTitleAndSourceAttribution() throws Exception {
        Long uncategorizedId = categories.findBySlug("uncategorized").orElseThrow().getId();
        Article excerpt = articleService.save(new ArticleCommand(null, "", "excerpt-source-test", "",
                ContentType.TEXT, ContentForm.EXCERPT, "读书破万卷，下笔如有神。", uncategorizedId,
                "", "杜甫《奉赠韦左丞丈二十二韵》", "https://example.com/source",
                false, false, "", ""));
        articleService.publish(excerpt.getId());

        String detail = mvc.perform(get("/posts/excerpt-source-test"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String articleMain = detail.substring(detail.indexOf("<main class=\"article-shell"), detail.indexOf("</main>"));
        assertThat(articleMain)
                .contains("<h1 class=\"sr-only\"")
                .contains("article-detail-source", "杜甫《奉赠韦左丞丈二十二韵》",
                        "href=\"https://example.com/source\"");
    }

    @Test
    void publicPagesExposeGeoMetadataAndChineseLocale() throws Exception {
        Long categoryId = categories.findBySlug("software-development").orElseThrow().getId();
        Article article = articleService.save(new ArticleCommand(null, "GEO 元数据验证", "geo-metadata-test", "检索摘要",
                ContentType.MARKDOWN, ContentForm.LONGFORM, "# GEO\n\n用于验证结构化数据。", categoryId,
                "GeoMetadata", "", "", false, true, "", ""));
        articleService.publish(article.getId());
        Article published = articles.findDetailedById(article.getId()).orElseThrow();
        String tagSlug = published.getTags().iterator().next().getSlug();

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Language", "zh-CN"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<link rel=\"canonical\" href=\"http://localhost/\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"@type\":\"WebSite\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"@type\":\"Blog\"")));

        mvc.perform(get("/posts/geo-metadata-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<meta property=\"og:type\" content=\"article\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("article:published_time")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("article:modified_time")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("article:section")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("article:tag")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"@type\":\"BlogPosting\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"@type\":\"BreadcrumbList\"")));

        mvc.perform(get("/search").param("q", "GEO"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<meta name=\"robots\" content=\"noindex,follow\"")));

        mvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "http://localhost/posts/geo-metadata-test")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "http://localhost/categories/software-development")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "http://localhost/tags/" + tagSlug)));
    }

    @Test
    void robotsAllowsChatGptSearchAndSeparatelyControlsTraining() throws Exception {
        try {
            settings.update(Map.of("site.ai-training", "disallow"));
            mvc.perform(get("/robots.txt"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/plain"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("User-agent: OAI-SearchBot\nAllow: /")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("User-agent: GPTBot\nDisallow: /")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "Sitemap: http://localhost/sitemap.xml")));

            settings.update(Map.of("site.ai-training", "allow"));
            mvc.perform(get("/robots.txt"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("User-agent: GPTBot\nAllow: /")));
        } finally {
            settings.update(Map.of("site.ai-training", "disallow"));
        }
    }

    @Test
    void expandedManualFormsRequireTitleAndSourceUrlMustBeHttp() {
        Long uncategorizedId = categories.findBySlug("uncategorized").orElseThrow().getId();
        assertThatThrownBy(() -> articleService.save(new ArticleCommand(null, "", "blank-longform-test", "",
                ContentType.TEXT, ContentForm.LONGFORM, "正文", uncategorizedId,
                "", "", "", false, false, "", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需要填写标题");
        assertThatThrownBy(() -> articleService.save(new ArticleCommand(null, "", "bad-source-test", "",
                ContentType.TEXT, ContentForm.EXCERPT, "摘录", uncategorizedId,
                "", "", "javascript:alert(1)", false, false, "", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP(S)");
    }

    @Test
    void standaloneRootIsLeafButCategoryDepthIsLimitedToTwoLevels() {
        Category standalone = taxonomy.createCategory("独立分类测试", "standalone-root-test", null,
                "", "", "", "", "");
        assertThat(taxonomy.requireLeaf(standalone.getId()).getId()).isEqualTo(standalone.getId());

        Category root = taxonomy.createCategory("层级根测试", "hierarchy-root-test", null,
                "", "", "", "", "");
        Category child = taxonomy.createCategory("层级子测试", "hierarchy-child-test", root.getId(),
                "", "", "", "", "");
        assertThatThrownBy(() -> taxonomy.requireLeaf(root.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taxonomy.createCategory("第三级", "hierarchy-third-test", child.getId(),
                "", "", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tagsCanBeRenamedAndMergedWithoutLosingArticleLinks() {
        Long categoryId = categories.findBySlug("software-development").orElseThrow().getId();
        Article article = articleService.save(new ArticleCommand(null, "标签合并测试", "tag-merge-test", "",
                ContentType.TEXT, ContentForm.NOTE, "标签合并正文", categoryId,
                "MergeSource, MergeTarget", "", "", false, true, "", ""));
        var source = tags.findByNormalizedName("mergesource").orElseThrow();
        var target = tags.findByNormalizedName("mergetarget").orElseThrow();

        taxonomy.mergeTag(source.getId(), target.getId());
        taxonomy.renameTag(target.getId(), "Merged Target");

        Article detailed = articles.findDetailedById(article.getId()).orElseThrow();
        assertThat(detailed.getTags()).extracting(tag -> tag.getName()).containsExactly("Merged Target");
        assertThat(tags.findById(source.getId())).isEmpty();
        assertThatThrownBy(() -> taxonomy.deleteTag(target.getId())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginFailuresAreRateLimitedPerAddress() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/admin/login").with(csrf())
                            .with(request -> { request.setRemoteAddr("203.0.113.88"); return request; })
                            .param("username", "admin").param("password", "wrong-secret"))
                    .andExpect(status().is3xxRedirection());
        }
        mvc.perform(post("/admin/login").with(csrf())
                        .with(request -> { request.setRemoteAddr("203.0.113.88"); return request; })
                        .param("username", "admin").param("password", "wrong-secret"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void missingPublicPageUsesCustom404() throws Exception {
        mvc.perform(get("/posts/not-a-real-article"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("这一页不在这里")));
    }

    @Test
    void mediaUploadUsesMagicBytesAndNeutralizesTraversalNames() throws Exception {
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        var asset = media.upload(new MockMultipartFile("file", "../../evil.png", "application/octet-stream", png));
        assertThat(asset.getOriginalName()).isEqualTo("evil.png");
        assertThat(asset.getRelativePath()).doesNotContain("..");
        assertThat(media.load(asset.getId()).resource().exists()).isTrue();

        assertThatThrownBy(() -> media.upload(new MockMultipartFile(
                "file", "double.png.exe", "image/png", new byte[] {0, 1, 2, 3})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> media.upload(new MockMultipartFile(
                "file", "double.png.exe", "image/png", png)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> media.upload(new MockMultipartFile(
                "file", "truncated.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
