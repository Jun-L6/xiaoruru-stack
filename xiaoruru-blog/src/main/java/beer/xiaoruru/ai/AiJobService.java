package beer.xiaoruru.ai;

import beer.xiaoruru.article.Article;
import beer.xiaoruru.article.ArticleRepository;
import beer.xiaoruru.article.ArticlePublishedEvent;
import beer.xiaoruru.article.ArticleSavedEvent;
import beer.xiaoruru.article.ClassificationSource;
import beer.xiaoruru.article.ClassificationStatus;
import beer.xiaoruru.config.BlogProperties;
import beer.xiaoruru.render.ContentRenderer;
import beer.xiaoruru.taxonomy.Category;
import beer.xiaoruru.taxonomy.CategoryRepository;
import beer.xiaoruru.taxonomy.TaxonomyService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiJobService {
    private static final Logger log = LoggerFactory.getLogger(AiJobService.class);
    private static final List<AiJobStatus> ACTIVE = List.of(AiJobStatus.PENDING, AiJobStatus.RUNNING);

    private final AiJobRepository jobs;
    private final AiExecutionLogRepository executionLogs;
    private final ArticleRepository articles;
    private final CategoryRepository categories;
    private final TaxonomyService taxonomy;
    private final ArticleClassifier classifier;
    private final ContentRenderer renderer;
    private final BlogProperties properties;
    private final TransactionTemplate transactions;
    private final ExecutorService aiExecutor;

    public AiJobService(AiJobRepository jobs, AiExecutionLogRepository executionLogs,
            ArticleRepository articles, CategoryRepository categories,
            TaxonomyService taxonomy, ArticleClassifier classifier, ContentRenderer renderer,
            BlogProperties properties, PlatformTransactionManager transactionManager,
            @Qualifier("aiTaskExecutor") ExecutorService aiExecutor) {
        this.jobs = jobs;
        this.executionLogs = executionLogs;
        this.articles = articles;
        this.categories = categories;
        this.taxonomy = taxonomy;
        this.classifier = classifier;
        this.renderer = renderer;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.aiExecutor = aiExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void articleSaved(ArticleSavedEvent event) {
        if (properties.ai().enabled() && properties.ai().classification().autoRun()) {
            enqueue(event.articleId(), event.contentHash(), properties.ai().classification().delayAfterSave());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void articlePublished(ArticlePublishedEvent event) {
        if (!properties.ai().enabled()) return;
        Article article = articles.findById(event.articleId()).orElse(null);
        if (article == null || article.isClassificationLocked()) return;
        boolean currentResult = event.contentHash().equals(article.getClassifiedContentHash())
                && (article.getClassificationStatus() == ClassificationStatus.APPLIED
                    || article.getClassificationStatus() == ClassificationStatus.REVIEW);
        if (!currentResult) {
            enqueue(event.articleId(), event.contentHash(), java.time.Duration.ZERO);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedJobs() {
        for (AiJob job : jobs.findByStatus(AiJobStatus.RUNNING)) {
            job.setStatus(AiJobStatus.PENDING);
            job.setAvailableAt(Instant.now());
            job.setErrorType("ApplicationRestarted");
            job.setErrorMessage("应用重启后自动恢复任务");
        }
    }

    @Transactional
    public AiJob requestNow(Long articleId) {
        if (!properties.ai().enabled()) {
            throw new IllegalStateException("AI 分类尚未启用，请先配置接口并设置 BLOG_AI_ENABLED=true");
        }
        Article article = articles.findById(articleId).orElseThrow(() -> new IllegalArgumentException("文章不存在"));
        article.setClassificationLocked(false);
        article.setClassificationStatus(ClassificationStatus.PENDING);
        return enqueue(articleId, article.getContentHash(), java.time.Duration.ZERO);
    }

    @Transactional
    public AiJob enqueue(Long articleId, String hash, java.time.Duration delay) {
        if (jobs.existsByArticleIdAndContentHashAndStatusIn(articleId, hash, ACTIVE)) {
            return jobs.findFirstByArticleIdAndContentHashAndStatusIn(articleId, hash, ACTIVE)
                    .orElse(null);
        }
        Article article = articles.findById(articleId).orElseThrow(() -> new IllegalArgumentException("文章不存在"));
        return jobs.save(new AiJob(article, hash, Instant.now().plus(delay)));
    }

    @Transactional
    public void retry(Long jobId) {
        if (!properties.ai().enabled()) {
            throw new IllegalStateException("AI 分类尚未启用");
        }
        AiJob job = jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("AI 任务不存在"));
        if (job.getStatus() != AiJobStatus.FAILED && job.getStatus() != AiJobStatus.CANCELLED) {
            throw new IllegalArgumentException("只有失败或已取消的任务可以重试");
        }
        Article article = job.getArticle();
        if (!article.getContentHash().equals(job.getContentHash())) {
            enqueue(article.getId(), article.getContentHash(), java.time.Duration.ZERO);
            return;
        }
        job.setStatus(AiJobStatus.PENDING);
        job.setAttemptCount(0);
        job.setAvailableAt(Instant.now());
        job.setStartedAt(null);
        job.setCompletedAt(null);
        job.setErrorType(null);
        job.setErrorMessage(null);
        article.setClassificationStatus(ClassificationStatus.PENDING);
    }

    @Scheduled(fixedDelayString = "${blog.ai.poll-delay:5s}")
    public void processNext() {
        if (!properties.ai().enabled()) return;
        Work work = transactions.execute(status -> claim());
        if (work == null) return;
        long startedNanos = System.nanoTime();
        try {
            ClassificationResult result = classifyWithTimeout(work.request());
            long elapsedMs = elapsedMillis(startedNanos);
            transactions.executeWithoutResult(status -> apply(work, result, elapsedMs));
        } catch (RuntimeException exception) {
            long elapsedMs = elapsedMillis(startedNanos);
            transactions.executeWithoutResult(status -> fail(work.jobId(), exception, elapsedMs));
        }
    }

    private ClassificationResult classifyWithTimeout(ArticleClassificationRequest request) {
        var future = aiExecutor.submit(() -> classifier.classify(request));
        try {
            return future.get(properties.ai().timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IllegalStateException("AI 分类请求超时", exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 分类线程被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("AI 分类执行失败", cause);
        }
    }

    private Work claim() {
        AiJob job = jobs.findFirstByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(AiJobStatus.PENDING, Instant.now())
                .orElse(null);
        if (job == null) return null;
        job.setStatus(AiJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.setAttemptCount(job.getAttemptCount() + 1);
        Article article = job.getArticle();
        String plain = renderer.toPlainText(article.getContent());
        if (plain.length() > 14_000) plain = plain.substring(0, 14_000);
        return new Work(job.getId(), new ArticleClassificationRequest(article.getId(), article.getTitle(),
                article.getSummary(), plain, job.getContentHash()));
    }

    private void apply(Work work, ClassificationResult result, long elapsedMs) {
        AiJob job = jobs.findById(work.jobId()).orElseThrow();
        Article article = articles.findById(work.request().articleId()).orElseThrow();
        if (!article.getContentHash().equals(work.request().contentHash()) || article.isClassificationLocked()) {
            job.setStatus(AiJobStatus.CANCELLED);
            job.setCompletedAt(Instant.now());
            AiExecutionLog execution = new AiExecutionLog(job, properties.ai().model(),
                    endpointIdentifier(), elapsedMs);
            execution.setParseStatus("CANCELLED");
            execution.setErrorType("ContentChangedOrLocked");
            execution.setErrorMessage("文章正文已变化或分类已被人工锁定，模型结果未应用");
            executionLogs.save(execution);
            return;
        }
        if (result == null || result.categoryId() == null
                || !Double.isFinite(result.confidence())
                || result.confidence() < 0 || result.confidence() > 1) {
            throw new IllegalArgumentException("模型返回的分类 ID 或置信度无效");
        }
        double confidence = result.confidence();
        BlogProperties.Classification thresholds = properties.ai().classification();
        Category category;
        if (confidence < thresholds.minimumConfidence()) {
            category = categories.findBySlug("uncategorized").orElseThrow();
            article.setClassificationStatus(ClassificationStatus.REVIEW);
        } else {
            category = taxonomy.requireLeaf(result.categoryId());
            article.setClassificationStatus(confidence >= thresholds.reviewConfidence()
                    ? ClassificationStatus.APPLIED : ClassificationStatus.REVIEW);
            List<String> resultTags = result.tags() == null ? List.of() : result.tags();
            LinkedHashSet<String> cleanedTags = resultTags.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip)
                    .filter(value -> value.length() <= 60)
                    .limit(thresholds.maxTags())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (cleanedTags.size() < thresholds.minTags()) {
                throw new IllegalArgumentException("模型返回的有效标签少于 " + thresholds.minTags() + " 个");
            }
            String tagText = String.join(",", cleanedTags);
            article.setTags(taxonomy.resolveTags(tagText, "AI"));
        }
        article.setCategory(category);
        article.setClassificationSource(ClassificationSource.AI);
        article.setClassificationConfidence(confidence);
        article.setClassificationReason(clip(result.reason(), 1000));
        article.setClassifiedContentHash(work.request().contentHash());
        article.setClassifiedAt(Instant.now());
        if ((article.getSummary() == null || article.getSummary().isBlank()) && result.summary() != null) {
            article.setSummary(clip(result.summary(), 240));
        }
        if ((article.getSeoDescription() == null || article.getSeoDescription().isBlank())
                && result.seoDescription() != null) {
            article.setSeoDescription(clip(result.seoDescription(), 500));
        }
        job.setStatus(AiJobStatus.SUCCEEDED);
        job.setCompletedAt(Instant.now());
        job.setErrorType(null);
        job.setErrorMessage(null);
        AiExecutionLog execution = new AiExecutionLog(job, properties.ai().model(),
                endpointIdentifier(), elapsedMs);
        execution.setParseStatus("SUCCEEDED");
        execution.setCategoryId(category.getId());
        execution.setConfidence(confidence);
        execution.setResultTags(safeResultTags(result.tags()));
        execution.setSuggestedCategory(clip(result.suggestedCategory(), 200));
        executionLogs.save(execution);
    }

    private void fail(Long jobId, RuntimeException exception, long elapsedMs) {
        AiJob job = jobs.findById(jobId).orElseThrow();
        job.setErrorType(exception.getClass().getSimpleName());
        job.setErrorMessage(clip(exception.getMessage(), 1000));
        if (job.getAttemptCount() < 3) {
            job.setStatus(AiJobStatus.PENDING);
            job.setAvailableAt(Instant.now().plus(30L * job.getAttemptCount(), ChronoUnit.SECONDS));
        } else {
            job.setStatus(AiJobStatus.FAILED);
            job.setCompletedAt(Instant.now());
            Article article = job.getArticle();
            if (article.getContentHash().equals(job.getContentHash())) {
                article.setClassificationStatus(ClassificationStatus.FAILED);
                categories.findBySlug("uncategorized").ifPresent(article::setCategory);
            }
        }
        AiExecutionLog execution = new AiExecutionLog(job, properties.ai().model(),
                endpointIdentifier(), elapsedMs);
        execution.setParseStatus("FAILED");
        execution.setErrorType(exception.getClass().getSimpleName());
        execution.setErrorMessage(clip(exception.getMessage(), 1000));
        executionLogs.save(execution);
        log.warn("AI classification job {} failed: {}", jobId, exception.getMessage());
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private String endpointIdentifier() {
        try {
            java.net.URI uri = java.net.URI.create(properties.ai().endpoint());
            return uri.getHost() == null ? "configured-endpoint" : uri.getHost();
        } catch (IllegalArgumentException ignored) {
            return "configured-endpoint";
        }
    }

    private static String clip(String value, int max) {
        if (value == null) return null;
        String safe = value.strip();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static String safeResultTags(List<String> tags) {
        if (tags == null) return null;
        String value = tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::strip)
                .collect(java.util.stream.Collectors.joining(", "));
        return clip(value, 500);
    }

    private record Work(Long jobId, ArticleClassificationRequest request) {}
}
