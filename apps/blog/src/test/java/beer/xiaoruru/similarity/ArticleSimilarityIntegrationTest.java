package beer.xiaoruru.similarity;

import static org.assertj.core.api.Assertions.assertThat;

import beer.xiaoruru.article.Article;
import beer.xiaoruru.article.ArticleCommand;
import beer.xiaoruru.article.ArticleRepository;
import beer.xiaoruru.article.ArticleService;
import beer.xiaoruru.article.ContentForm;
import beer.xiaoruru.article.ContentType;
import beer.xiaoruru.taxonomy.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:similarity_test;DB_CLOSE_DELAY=-1",
        "blog.ai.poll-delay=1h", "blog.ai.classification.auto-run=false"
})
class ArticleSimilarityIntegrationTest {
    @Autowired ArticleService articleService;
    @Autowired ArticleRepository articles;
    @Autowired CategoryRepository categories;
    @Autowired ArticleSimilarityService similarity;

    @BeforeEach
    void reset() { articles.deleteAll(); }

    @Test
    void exactPublishedContentRequiresConfirmationWithoutEmbedding() {
        Article published = articleService.save(command("原文章", """
                ## 小内存构建

                在资源有限的服务器上构建应用，需要限制并发并利用依赖缓存。
                """));
        articleService.publish(published.getId());
        Article draft = articleService.save(command("准备导入", published.getContent()));

        ArticleSimilarityCheck result = similarity.check(draft.getId());

        assertThat(result.getRisk()).isEqualTo(SimilarityRisk.EXACT);
        assertThat(result.getMatchedArticle().getId()).isEqualTo(published.getId());
        assertThat(result.getLexicalScore()).isEqualTo(1);
        assertThat(result.getSemanticScore()).isNull();
    }

    private ArticleCommand command(String title, String content) {
        Long category = categories.findBySlug("software-development").orElseThrow().getId();
        return new ArticleCommand(null, title, "", "", ContentType.MARKDOWN, ContentForm.LONGFORM,
                content, category, "", "", "", false, true, "", "");
    }
}
