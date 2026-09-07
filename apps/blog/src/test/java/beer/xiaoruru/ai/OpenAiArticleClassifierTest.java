package beer.xiaoruru.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import beer.xiaoruru.article.ContentForm;
import beer.xiaoruru.taxonomy.Category;
import beer.xiaoruru.taxonomy.CategoryRepository;
import beer.xiaoruru.taxonomy.TagRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class OpenAiArticleClassifierTest {

    @Test
    void usesPersonalBlogRulesAndSemanticSlugs() {
        OpenAiChatGateway gateway = mock(OpenAiChatGateway.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        TagRepository tags = mock(TagRepository.class);
        Category root = new Category("思考与表达", "thoughts-expression");
        Category leaf = new Category("片语与诗", "snippets-poetry");
        leaf.setParent(root);
        leaf.setAiDescription("原创短句或短诗");
        leaf.setAiExclusions("有明确出处的外部摘录");
        leaf.setAiExamples("月光落进杯里");
        leaf.setAiKeywords("片语,短诗");
        when(categories.findEnabledLeaves()).thenReturn(List.of(leaf));
        when(tags.findAllSorted()).thenReturn(List.of());
        when(gateway.complete(any(), anyString(), anyString())).thenReturn("""
                {"categorySlug":"snippets-poetry","contentForm":"MOMENT","confidence":0.88,
                 "tags":[],"reason":"原创式诗性短句","summary":"月光片段",
                 "seoDescription":null,"suggestedCategory":null,"ignored":"safe"}
                """);
        var classifier = new OpenAiArticleClassifier(gateway, categories, tags, new ObjectMapper());

        ClassificationResult result = classifier.classify(new ArticleClassificationRequest(
                1L, "晚风", "", "晚风把月光吹进杯里。", "hash"), mockConnection());

        assertThat(result.categorySlug()).isEqualTo("snippets-poetry");
        assertThat(result.contentForm()).isEqualTo(ContentForm.MOMENT);
        assertThat(result.tags()).isEmpty();
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(gateway).complete(any(), system.capture(), user.capture());
        assertThat(system.getValue()).contains("综合型个人博客", "内容短、非技术、只有一句话", "0 到 5 个");
        assertThat(user.getValue()).contains("SLUG=snippets-poetry", "EXCLUDE=有明确出处", "晚风把月光吹进杯里");
        assertThat(user.getValue()).doesNotContain("ID=");
    }

    private AiSettingsService.Connection mockConnection() {
        return new AiSettingsService.Connection(java.net.URI.create("https://example.com/v1/chat/completions"),
                "key", "model", java.time.Duration.ofSeconds(1), null);
    }
}
