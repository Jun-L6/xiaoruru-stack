package beer.xiaoruru.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import beer.xiaoruru.article.Article;
import beer.xiaoruru.article.ArticleCommand;
import beer.xiaoruru.article.ArticleRepository;
import beer.xiaoruru.article.ArticleService;
import beer.xiaoruru.article.ClassificationStatus;
import beer.xiaoruru.article.ContentType;
import beer.xiaoruru.taxonomy.CategoryRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "blog.ai.enabled=true",
        "blog.ai.classification.auto-run=false",
        "blog.ai.poll-delay=1h",
        "blog.ai.timeout=200ms"
})
class AiClassificationIntegrationTest {
    @MockitoBean ArticleClassifier classifier;
    @Autowired AiJobService service;
    @Autowired AiJobRepository jobs;
    @Autowired AiExecutionLogRepository logs;
    @Autowired ArticleService articleService;
    @Autowired ArticleRepository articles;
    @Autowired CategoryRepository categories;

    @BeforeEach
    void resetClassifier() {
        reset(classifier);
    }

    @Test
    void appliesHighConfidenceAndMarksMiddleConfidenceForReview() {
        Long javaId = categories.findBySlug("java").orElseThrow().getId();
        Article high = article("ai-high-confidence", false);
        when(classifier.classify(any())).thenReturn(new ClassificationResult(javaId, 0.91,
                List.of("Java", "JVM"), "主题明确", "AI 摘要", "SEO", null));
        service.requestNow(high.getId());
        service.processNext();

        Article applied = articles.findDetailedById(high.getId()).orElseThrow();
        assertThat(applied.getClassificationStatus()).isEqualTo(ClassificationStatus.APPLIED);
        assertThat(applied.getCategory().getId()).isEqualTo(javaId);
        assertThat(applied.getTags()).extracting(tag -> tag.getName()).containsExactlyInAnyOrder("Java", "JVM");

        Article middle = article("ai-middle-confidence", false);
        when(classifier.classify(any())).thenReturn(new ClassificationResult(javaId, 0.70,
                List.of("Spring", "JPA"), "需要复核", null, null, null));
        service.requestNow(middle.getId());
        service.processNext();
        assertThat(articles.findById(middle.getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.REVIEW);
    }

    @Test
    void lowConfidenceFallsBackToUncategorized() {
        Long javaId = categories.findBySlug("java").orElseThrow().getId();
        Article article = article("ai-low-confidence", false);
        when(classifier.classify(any())).thenReturn(new ClassificationResult(javaId, 0.20,
                List.of(), "无法确定", null, null, "新分类建议"));
        service.requestNow(article.getId());
        service.processNext();

        Article result = articles.findDetailedById(article.getId()).orElseThrow();
        assertThat(result.getClassificationStatus()).isEqualTo(ClassificationStatus.REVIEW);
        assertThat(result.getCategory().getSlug()).isEqualTo("uncategorized");
        assertThat(logs.findTop100ByOrderByCreatedAtDesc()).anySatisfy(log -> {
            assertThat(log.getJob().getArticle().getId()).isEqualTo(article.getId());
            assertThat(log.getSuggestedCategory()).isEqualTo("新分类建议");
        });
    }

    @Test
    void invalidOutputAndTimeoutAreRetriedWithoutBlockingArticle() {
        Article invalid = article("ai-invalid-output", false);
        when(classifier.classify(any())).thenReturn(new ClassificationResult(null, 2.0,
                List.of("OnlyOne"), "bad", null, null, null));
        AiJob invalidJob = service.requestNow(invalid.getId());
        service.processNext();
        AiJob afterInvalid = jobs.findById(invalidJob.getId()).orElseThrow();
        assertThat(afterInvalid.getStatus()).isEqualTo(AiJobStatus.PENDING);
        assertThat(afterInvalid.getAttemptCount()).isEqualTo(1);

        afterInvalid.setAvailableAt(java.time.Instant.now().plusSeconds(3600));
        jobs.saveAndFlush(afterInvalid);
        Article timeout = article("ai-timeout", false);
        when(classifier.classify(any())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return null;
        });
        AiJob timeoutJob = service.requestNow(timeout.getId());
        service.processNext();
        AiJob afterTimeout = jobs.findById(timeoutJob.getId()).orElseThrow();
        assertThat(afterTimeout.getStatus()).isEqualTo(AiJobStatus.PENDING);
        assertThat(afterTimeout.getErrorMessage()).contains("超时");
    }

    @Test
    void manualClassificationLockCancelsStaleAiResult() {
        Long javaId = categories.findBySlug("java").orElseThrow().getId();
        Article article = article("ai-manual-lock", false);
        AiJob job = service.enqueue(article.getId(), article.getContentHash(), java.time.Duration.ZERO);
        articleService.save(new ArticleCommand(article.getId(), article.getTitle(), article.getSlug(),
                article.getSummary(), article.getContentType(), article.getContent(), article.getCategory().getId(),
                "Manual", false, true, "", ""));
        when(classifier.classify(any())).thenReturn(new ClassificationResult(javaId, 0.99,
                List.of("Java", "JVM"), "would overwrite", null, null, null));
        service.processNext();

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(AiJobStatus.CANCELLED);
        Article unchanged = articles.findDetailedById(article.getId()).orElseThrow();
        assertThat(unchanged.isClassificationLocked()).isTrue();
        assertThat(unchanged.getTags()).extracting(tag -> tag.getName()).containsExactly("Manual");
    }

    private Article article(String slug, boolean locked) {
        Long uncategorized = categories.findBySlug("uncategorized").orElseThrow().getId();
        return articleService.save(new ArticleCommand(null, "AI 测试 " + slug, slug, "",
                ContentType.MARKDOWN, "# Java\n\nJVM 与 Spring 技术文章", uncategorized, "",
                false, locked, "", ""));
    }
}
