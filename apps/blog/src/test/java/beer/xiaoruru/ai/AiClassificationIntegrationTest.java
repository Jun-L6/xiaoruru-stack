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
import beer.xiaoruru.article.ContentForm;
import beer.xiaoruru.taxonomy.CategoryRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "blog.ai.classification.auto-run=false",
        "blog.ai.poll-delay=1h"
})
class AiClassificationIntegrationTest {
    @MockitoBean ArticleClassifier classifier;
    @Autowired AiJobService service;
    @Autowired AiJobRepository jobs;
    @Autowired AiExecutionLogRepository logs;
    @Autowired ArticleService articleService;
    @Autowired ArticleRepository articles;
    @Autowired CategoryRepository categories;
    @Autowired AiSettingsService settings;

    @BeforeEach
    void resetClassifier() {
        reset(classifier);
        settings.save(new AiSettingsService.Form("cpa", "", "test-key", "test-model",
                "/v1/chat/completions", 1, null, false));
    }

    @Test
    void appliesHighConfidenceAndMarksMiddleConfidenceForReview() {
        String categorySlug = "software-development";
        Article high = article("ai-high-confidence", false);
        when(classifier.classify(any(), any())).thenReturn(new ClassificationResult(categorySlug, ContentForm.LONGFORM, 0.91,
                List.of("Java", "JVM"), "主题明确", "AI 摘要", "SEO", null));
        service.requestNow(high.getId());
        service.processNext();

        Article applied = articles.findDetailedById(high.getId()).orElseThrow();
        assertThat(applied.getClassificationStatus()).isEqualTo(ClassificationStatus.APPLIED);
        assertThat(applied.getCategory().getSlug()).isEqualTo(categorySlug);
        assertThat(applied.getContentForm()).isEqualTo(ContentForm.LONGFORM);
        assertThat(applied.getTags()).extracting(tag -> tag.getName()).containsExactlyInAnyOrder("Java", "JVM");

        Article middle = article("ai-middle-confidence", false);
        when(classifier.classify(any(), any())).thenReturn(new ClassificationResult(categorySlug, ContentForm.NOTE, 0.70,
                List.of("Spring", "JPA"), "需要复核", null, null, null));
        service.requestNow(middle.getId());
        service.processNext();
        assertThat(articles.findById(middle.getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.REVIEW);
    }

    @Test
    void lowConfidenceFallsBackToUncategorized() {
        Article article = article("ai-low-confidence", false);
        when(classifier.classify(any(), any())).thenReturn(new ClassificationResult("software-development", ContentForm.NOTE, 0.20,
                List.of(), "无法确定", null, null, "新分类建议"));
        service.requestNow(article.getId());
        service.processNext();

        Article result = articles.findDetailedById(article.getId()).orElseThrow();
        assertThat(result.getClassificationStatus()).isEqualTo(ClassificationStatus.REVIEW);
        assertThat(result.getCategory().getSlug()).isEqualTo("uncategorized");
        assertThat(logs.findTop100ByOrderByCreatedAtDesc()).anySatisfy(log -> {
            assertThat(log.getJob().getArticle().getId()).isEqualTo(article.getId());
            assertThat(log.getCategorySlug()).isEqualTo("uncategorized");
            assertThat(log.getContentForm()).isEqualTo("NOTE");
            assertThat(log.getSuggestedCategory()).isEqualTo("新分类建议");
        });
    }

    @Test
    void invalidOutputAndTimeoutFailWithoutAutomaticRetriesOrBlockingArticle() {
        Article invalid = article("ai-invalid-output", false);
        when(classifier.classify(any(), any())).thenReturn(new ClassificationResult(null, null, 2.0,
                List.of("OnlyOne"), "bad", null, null, null));
        AiJob invalidJob = service.requestNow(invalid.getId());
        service.processNext();
        AiJob afterInvalid = jobs.findById(invalidJob.getId()).orElseThrow();
        assertThat(afterInvalid.getStatus()).isEqualTo(AiJobStatus.FAILED);
        assertThat(afterInvalid.getAttemptCount()).isEqualTo(1);

        afterInvalid.setAvailableAt(java.time.Instant.now().plusSeconds(3600));
        jobs.saveAndFlush(afterInvalid);
        Article timeout = article("ai-timeout", false);
        when(classifier.classify(any(), any())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return null;
        });
        AiJob timeoutJob = service.requestNow(timeout.getId());
        service.processNext();
        AiJob afterTimeout = jobs.findById(timeoutJob.getId()).orElseThrow();
        assertThat(afterTimeout.getStatus()).isEqualTo(AiJobStatus.FAILED);
        assertThat(afterTimeout.getErrorMessage()).contains("超时");
    }

    @Test
    void manualClassificationLockCancelsStaleAiResult() {
        Article article = article("ai-manual-lock", false);
        AiJob job = service.enqueue(article.getId(), article.getContentHash(), java.time.Duration.ZERO);
        articleService.save(new ArticleCommand(article.getId(), article.getTitle(), article.getSlug(),
                article.getSummary(), article.getContentType(), article.getContentForm(), article.getContent(), article.getCategory().getId(),
                "Manual", false, true, "", ""));
        when(classifier.classify(any(), any())).thenReturn(new ClassificationResult(
                "software-development", ContentForm.LONGFORM, 0.99,
                List.of("Java", "JVM"), "would overwrite", null, null, null));
        service.processNext();

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(AiJobStatus.CANCELLED);
        Article unchanged = articles.findDetailedById(article.getId()).orElseThrow();
        assertThat(unchanged.isClassificationLocked()).isTrue();
        assertThat(unchanged.getTags()).extracting(tag -> tag.getName()).containsExactly("Manual");
        org.mockito.Mockito.verifyNoInteractions(classifier);
    }

    @Test
    void obsoleteContentIsCancelledBeforeMakingAPaidRequest() {
        Article article = article("ai-obsolete-body", false);
        AiJob job = service.enqueue(article.getId(), article.getContentHash(), java.time.Duration.ZERO);
        article.setContentHash("changed-content");
        articles.saveAndFlush(article);
        service.processNext();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(AiJobStatus.CANCELLED);
        org.mockito.Mockito.verifyNoInteractions(classifier);
    }

    @Test
    void immediateRequestBringsForwardDelayedJobWithoutDuplicatingIt() {
        Article article = article("ai-immediate", false);
        AiJob delayed = service.enqueue(article.getId(), article.getContentHash(), java.time.Duration.ofHours(1));
        AiJob immediate = service.requestNow(article.getId());
        assertThat(immediate.getId()).isEqualTo(delayed.getId());
        assertThat(immediate.getAvailableAt()).isBeforeOrEqualTo(java.time.Instant.now());
        when(classifier.classify(any(), any())).thenThrow(new IllegalStateException("test failure"));
        service.processNext();
        assertThat(jobs.findById(delayed.getId()).orElseThrow().getStatus()).isEqualTo(AiJobStatus.FAILED);
    }

    @Test
    void changingSettingsDuringCallKeepsOriginalAuditAndManualLock() {
        Article article = article("ai-in-flight-settings", false);
        AiJob job = service.requestNow(article.getId());
        when(classifier.classify(any(), any())).thenAnswer(invocation -> {
            AiSettingsService.Connection connection = invocation.getArgument(1);
            assertThat(connection.model()).isEqualTo("test-model");
            settings.save(new AiSettingsService.Form("cpa", "", "changed-key", "changed-model",
                    "/v1/chat/completions", 20, null, false));
            Article locked = articles.findById(article.getId()).orElseThrow();
            locked.setClassificationLocked(true);
            locked.setClassificationStatus(ClassificationStatus.APPLIED);
            articles.saveAndFlush(locked);
            throw new IllegalStateException("upstream failed");
        });
        service.processNext();
        assertThat(articles.findById(article.getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.APPLIED);
        assertThat(logs.findTop100ByOrderByCreatedAtDesc()).anySatisfy(execution -> {
            assertThat(execution.getJob().getId()).isEqualTo(job.getId());
            assertThat(execution.getModel()).isEqualTo("test-model");
        });
    }

    @Test
    void appliesShortPoeticExpressionWithoutForcingTagsOrTechnicalCategories() {
        Article article = articleService.save(new ArticleCommand(null, "晚风", "ai-short-poem", "",
                ContentType.TEXT, ContentForm.LONGFORM, "晚风把月光吹进了杯里。",
                categories.findBySlug("uncategorized").orElseThrow().getId(), "",
                false, false, "", ""));
        when(classifier.classify(any(), any())).thenReturn(new ClassificationResult(
                "snippets-poetry", ContentForm.MOMENT, 0.86, List.of(),
                "这是无外部出处的原创式诗性短句", "晚风与月光的一瞬。", null, null));

        service.requestNow(article.getId());
        service.processNext();

        Article classified = articles.findDetailedById(article.getId()).orElseThrow();
        assertThat(classified.getCategory().getSlug()).isEqualTo("snippets-poetry");
        assertThat(classified.getContentForm()).isEqualTo(ContentForm.MOMENT);
        assertThat(classified.getClassificationStatus()).isEqualTo(ClassificationStatus.APPLIED);
        assertThat(classified.getTags()).isEmpty();
    }

    private Article article(String slug, boolean locked) {
        Long uncategorized = categories.findBySlug("uncategorized").orElseThrow().getId();
        return articleService.save(new ArticleCommand(null, "AI 测试 " + slug, slug, "",
                ContentType.MARKDOWN, ContentForm.LONGFORM, "# Java\n\nJVM 与 Spring 技术文章", uncategorized, "",
                false, locked, "", ""));
    }
}
