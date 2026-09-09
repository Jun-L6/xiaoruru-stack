package beer.xiaoruru.conversation;

import beer.xiaoruru.ai.AiSettingsService;
import beer.xiaoruru.article.Article;
import beer.xiaoruru.article.ArticleService;
import beer.xiaoruru.article.ContentForm;
import beer.xiaoruru.article.GeneratedArticleDraft;
import beer.xiaoruru.similarity.ArticleSimilarityService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConversationImportService {
    private static final Logger log = LoggerFactory.getLogger(ConversationImportService.class);
    private final ConversationImportRepository imports;
    private final ShareConversationService shares;
    private final ConversationPostGenerator generator;
    private final ArticleService articleService;
    private final ArticleSimilarityService similarity;
    private final AiSettingsService aiSettings;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final ExecutorService aiExecutor;

    public ConversationImportService(ConversationImportRepository imports, ShareConversationService shares,
            ConversationPostGenerator generator, ArticleService articleService,
            ArticleSimilarityService similarity, AiSettingsService aiSettings, ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            @Qualifier("aiTaskExecutor") ExecutorService aiExecutor) {
        this.imports = imports;
        this.shares = shares;
        this.generator = generator;
        this.articleService = articleService;
        this.similarity = similarity;
        this.aiSettings = aiSettings;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.aiExecutor = aiExecutor;
    }

    public ConversationImport extract(String sourceUrl) {
        String normalized = sourceUrl == null ? "" : sourceUrl.strip();
        ConversationSnapshot snapshot = shares.extract(normalized);
        return imports.save(new ConversationImport(normalized, snapshot, mapper.writeValueAsString(snapshot)));
    }

    @Transactional(readOnly = true)
    public ConversationImport require(Long id) {
        return imports.findDetailedById(id).orElseThrow(() -> new IllegalArgumentException("导入任务不存在"));
    }

    @Transactional(readOnly = true)
    public ConversationSnapshot snapshot(Long id) {
        ConversationImport job = imports.findById(id).orElseThrow(() -> new IllegalArgumentException("导入任务不存在"));
        if (job.getSnapshot() == null || job.getSnapshot().equals("{}")) return null;
        return mapper.readValue(job.getSnapshot(), ConversationSnapshot.class);
    }

    @Transactional
    public void requestGeneration(Long id) {
        if (!aiSettings.enabled()) throw new IllegalStateException("请先在 AI 设置中启用文章生成模型。");
        ConversationImport job = imports.findById(id).orElseThrow(() -> new IllegalArgumentException("导入任务不存在"));
        if (!(job.getStatus() == ConversationImportStatus.EXTRACTED || job.getStatus() == ConversationImportStatus.FAILED)) {
            throw new IllegalArgumentException("当前任务状态不能提交生成。");
        }
        if (job.getSnapshot() == null || job.getSnapshot().equals("{}")) {
            throw new IllegalStateException("原始对话已经清理，无法重新生成。");
        }
        job.setStatus(ConversationImportStatus.PENDING);
        job.setStartedAt(null);
        job.setCompletedAt(null);
        job.setErrorType(null);
        job.setErrorMessage(null);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterrupted() {
        for (ConversationImport job : imports.findByStatus(ConversationImportStatus.RUNNING)) {
            job.setStatus(ConversationImportStatus.FAILED);
            job.setCompletedAt(Instant.now());
            job.setErrorType("ApplicationRestarted");
            job.setErrorMessage("应用重启中断了生成任务，可以重新提交。");
        }
    }

    @Scheduled(fixedDelayString = "${blog.ai.poll-delay:5s}")
    public void processNext() {
        AiSettingsService.Selection selection = aiSettings.selection();
        if (!selection.enabled()) return;
        Work work = transactions.execute(status -> claim(selection));
        if (work == null) return;
        try {
            GeneratedPost post = generate(work);
            Long articleId = transactions.execute(status -> apply(work, post));
            if (articleId != null) {
                try { similarity.check(articleId); }
                catch (RuntimeException exception) {
                    log.warn("Initial similarity check for imported article {} failed: {}", articleId, exception.getMessage());
                }
            }
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> fail(work.id(), exception));
        }
    }

    private Work claim(AiSettingsService.Selection selection) {
        ConversationImport job = imports.findFirstByStatusOrderByCreatedAtAsc(ConversationImportStatus.PENDING)
                .orElse(null);
        if (job == null) return null;
        job.setStatus(ConversationImportStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.setAttemptCount(job.getAttemptCount() + 1);
        ConversationSnapshot snapshot = mapper.readValue(job.getSnapshot(), ConversationSnapshot.class);
        return new Work(job.getId(), job.getSourceUrl(), snapshot, selection);
    }

    private GeneratedPost generate(Work work) {
        var future = aiExecutor.submit(() -> generator.generate(work.snapshot(), aiSettings.connection(work.selection())));
        try {
            return future.get(20, TimeUnit.MINUTES);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IllegalStateException("对话转文章超过 20 分钟，任务已停止。");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("对话生成线程被中断。");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("对话生成失败。");
        }
    }

    private Long apply(Work work, GeneratedPost post) {
        ConversationImport job = imports.findById(work.id()).orElseThrow();
        if (job.getStatus() != ConversationImportStatus.RUNNING) return null;
        ContentForm form;
        try { form = ContentForm.valueOf(post.contentForm()); }
        catch (IllegalArgumentException exception) { form = ContentForm.LONGFORM; }
        Article article = articleService.saveGenerated(new GeneratedArticleDraft(post.title(), post.summary(),
                post.markdown(), post.categorySlug(), form, post.tags(), work.sourceUrl()));
        job.setArticle(article);
        job.setStatus(ConversationImportStatus.READY);
        job.setCompletedAt(Instant.now());
        job.setErrorType(null);
        job.setErrorMessage(null);
        // 草稿生成成功后不再长期保留完整第三方对话副本。
        job.setSnapshot("{}");
        return article.getId();
    }

    private void fail(Long id, RuntimeException exception) {
        ConversationImport job = imports.findById(id).orElseThrow();
        job.setStatus(ConversationImportStatus.FAILED);
        job.setCompletedAt(Instant.now());
        job.setErrorType(exception.getClass().getSimpleName());
        job.setErrorMessage(clip(exception.getMessage(), 1000));
        log.warn("Conversation import {} failed: {}", id, exception.getMessage());
    }

    private static String clip(String value, int max) {
        if (value == null) return "未知错误";
        String safe = value.strip();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private record Work(Long id, String sourceUrl, ConversationSnapshot snapshot,
                        AiSettingsService.Selection selection) {}
}
