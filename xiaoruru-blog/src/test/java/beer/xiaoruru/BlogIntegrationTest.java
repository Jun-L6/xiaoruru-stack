package beer.xiaoruru;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import beer.xiaoruru.taxonomy.Category;
import beer.xiaoruru.taxonomy.CategoryRepository;
import beer.xiaoruru.taxonomy.TagRepository;
import beer.xiaoruru.taxonomy.TaxonomyService;
import beer.xiaoruru.media.MediaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.util.Base64;

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

    @Test
    void publicPagesAreAvailableAndAdminIsProtected() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("site/index"));
        mvc.perform(get("/categories"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("计算机基础")));
        mvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void savesPublishesAndDisplaysMarkdownArticle() throws Exception {
        Long categoryId = categories.findBySlug("java").orElseThrow().getId();
        Article article = articleService.save(new ArticleCommand(null, "JVM 字节码入门", "jvm-bytecode-test", "",
                ContentType.MARKDOWN, "# JVM\n\n```java\nclass Demo {}\n```", categoryId,
                "Java, JVM", false, true, "", ""));
        articleService.publish(article.getId());

        mvc.perform(get("/posts/jvm-bytecode-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("JVM 字节码入门")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("language-java")));
    }

    @Test
    void authenticatedAdminCanOpenEditorAndCsrfProtectsWrites() throws Exception {
        mvc.perform(get("/admin/articles/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/article-edit"));
        mvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("admin/dashboard"));
        mvc.perform(get("/admin/articles").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("admin/articles"));
        mvc.perform(get("/admin/categories").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("admin/categories"));
        mvc.perform(get("/admin/tags").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("admin/tags"));

        mvc.perform(post("/admin/settings").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/admin/settings").with(user("admin").roles("ADMIN")).with(csrf())
                        .param("site.name", "测试博客"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/settings"));
    }

    @Test
    void existingArticleEditorLoadsDetailedRelationships() throws Exception {
        Long categoryId = categories.findBySlug("c-cpp").orElseThrow().getId();
        Article article = articleService.save(new ArticleCommand(null, "编辑关系测试", "editor-relations-test", "",
                ContentType.MARKDOWN, "# 内容", categoryId, "C++, ABI", false, true, "", ""));

        mvc.perform(get("/admin/articles/" + article.getId()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/article-edit"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("C++")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ABI")));
    }

    @Test
    void standaloneRootIsLeafButCategoryDepthIsLimitedToTwoLevels() {
        Category standalone = taxonomy.createCategory("独立分类测试", "standalone-root-test", null, "", "", "");
        assertThat(taxonomy.requireLeaf(standalone.getId()).getId()).isEqualTo(standalone.getId());

        Category root = taxonomy.createCategory("层级根测试", "hierarchy-root-test", null, "", "", "");
        Category child = taxonomy.createCategory("层级子测试", "hierarchy-child-test", root.getId(), "", "", "");
        assertThatThrownBy(() -> taxonomy.requireLeaf(root.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taxonomy.createCategory("第三级", "hierarchy-third-test", child.getId(), "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tagsCanBeRenamedAndMergedWithoutLosingArticleLinks() {
        Long categoryId = categories.findBySlug("assembly").orElseThrow().getId();
        Article article = articleService.save(new ArticleCommand(null, "标签合并测试", "tag-merge-test", "",
                ContentType.TEXT, "标签合并正文", categoryId, "MergeSource, MergeTarget", false, true, "", ""));
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
