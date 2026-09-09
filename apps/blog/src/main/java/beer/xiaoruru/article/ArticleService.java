package beer.xiaoruru.article;

import beer.xiaoruru.common.Hashing;
import beer.xiaoruru.render.ContentRenderer;
import beer.xiaoruru.taxonomy.Category;
import beer.xiaoruru.taxonomy.TaxonomyService;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文章写入用例的统一入口。
 *
 * <p>该服务在同一个事务中完成输入规则校验、正文渲染、分类/标签关联和版本快照。
 * 提交后再通过领域事件触发 AI 任务，避免模型调用读到未提交的文章状态。
 */
@Service
public class ArticleService {
    private final ArticleRepository articles;
    private final ArticleRevisionRepository revisions;
    private final TaxonomyService taxonomy;
    private final ContentRenderer renderer;
    private final ApplicationEventPublisher events;

    public ArticleService(ArticleRepository articles, ArticleRevisionRepository revisions,
            TaxonomyService taxonomy, ContentRenderer renderer,
            ApplicationEventPublisher events) {
        this.articles = articles;
        this.revisions = revisions;
        this.taxonomy = taxonomy;
        this.renderer = renderer;
        this.events = events;
    }

    @Transactional
    public Article save(ArticleCommand command) {
        boolean isNew = command.id() == null;
        Category category = taxonomy.requireLeaf(command.categoryId());
        Article article = isNew ? new Article("未命名内容", uniqueSlug(command.slug()), category)
                : articles.findById(command.id()).orElseThrow(() -> new IllegalArgumentException("文章不存在"));

        // 先保留会影响 AI 所有权判断的原状态，再应用本次编辑输入。
        String previousHash = article.getContentHash();
        Long previousCategoryId = article.getCategory() == null ? null : article.getCategory().getId();
        ContentForm previousContentForm = article.getContentForm();
        boolean previousContentFormAutomatic = article.isContentFormAutomatic();
        String previousSummary = article.getSummary();
        SummaryOrigin previousSummaryOrigin = article.getSummaryOrigin();
        boolean automaticContentForm = command.contentForm() == null;
        ContentForm contentForm = automaticContentForm ? article.getContentForm() : command.contentForm();
        if (contentForm == null) contentForm = ContentForm.LONGFORM;

        // 标题是跨字段规则：动态/摘录可使用内部标题，其他人工形态必须有作者标题。
        String submittedTitle = blankToNull(command.title());
        if (!automaticContentForm && !contentForm.isTitleOptional() && submittedTitle == null) {
            throw new IllegalArgumentException(contentForm.getLabel() + "需要填写标题");
        }
        String plainContent = renderer.toPlainText(command.content());
        String title = submittedTitle == null ? generatedTitle(plainContent) : submittedTitle;
        if (!isNew && article.getStatus() == ArticleStatus.PUBLISHED
                && hasMeaningfulChange(article, command, contentForm, automaticContentForm, title)) {
            saveRevision(article, "BEFORE_UPDATE");
        }
        article.setTitle(title);
        article.setTitleOrigin(submittedTitle == null ? TitleOrigin.GENERATED : TitleOrigin.MANUAL);
        if (isNew || article.getStatus() != ArticleStatus.PUBLISHED) {
            article.setSlug(uniqueSlugFor(command.slug(), article.getId(), article.getSlug()));
        }
        applySummary(article, blankToNull(command.summary()), previousSummary, previousSummaryOrigin,
                plainContent, isNew);
        article.setContentType(command.contentType());
        article.setContentForm(contentForm);
        article.setContentFormAutomatic(automaticContentForm);
        article.setSourceCitation(blankToNull(command.sourceCitation()));
        article.setSourceUrl(normalizeSourceUrl(command.sourceUrl()));
        article.setContent(command.content());
        article.setContentHash(Hashing.sha256(command.content()));
        article.setRenderedHtml(renderer.render(command.contentType(), command.content()));
        article.setRenderVersion(ContentRenderer.RENDER_VERSION);
        article.setCategory(category);
        article.setTags(taxonomy.resolveTags(command.tags(), "MANUAL"));
        article.setPinned(Boolean.TRUE.equals(command.pinned()));
        article.setSeoTitle(blankToNull(command.seoTitle()));
        article.setSeoDescription(blankToNull(command.seoDescription()));
        article.setClassificationLocked(Boolean.TRUE.equals(command.classificationLocked()));

        // 人工选择分类或内容形态即取得所有权；切回自动模式时才将它交还 AI。
        boolean manualCategory = isNew ? !"uncategorized".equals(category.getSlug())
                : previousCategoryId != null && !previousCategoryId.equals(category.getId());
        boolean switchedToAutomatic = !isNew && !previousContentFormAutomatic && automaticContentForm;
        if (switchedToAutomatic && !manualCategory) {
            article.setClassificationLocked(false);
        }
        if (!automaticContentForm || manualCategory) {
            article.setClassificationSource(ClassificationSource.MANUAL);
            article.setClassificationLocked(true);
            article.setClassificationStatus(ClassificationStatus.APPLIED);
        }

        Article saved = articles.save(article);
        // 只对未锁定且内容确实变化的文章发出任务，去重由 AI 队列完成。
        if (!saved.isClassificationLocked()
                && (!saved.getContentHash().equals(previousHash) || switchedToAutomatic)) {
            saved.setClassificationStatus(ClassificationStatus.PENDING);
            events.publishEvent(new ArticleSavedEvent(saved.getId(), saved.getContentHash()));
        }
        return saved;
    }

    /** 保存已由对话生成器完成标题、分类和标签整理的草稿，不再重复触发自动分类。 */
    @Transactional
    public Article saveGenerated(GeneratedArticleDraft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank()
                || draft.markdown() == null || draft.markdown().isBlank()) {
            throw new IllegalArgumentException("生成结果缺少标题或正文");
        }
        Category category = taxonomy.requireLeafBySlug(draft.categorySlug());
        ContentForm form = draft.contentForm() == null ? ContentForm.LONGFORM : draft.contentForm();
        String tags = draft.tags() == null ? "" : draft.tags().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip).limit(5)
                .collect(java.util.stream.Collectors.joining(","));
        Article article = save(new ArticleCommand(null, clip(draft.title().strip(), 200), "",
                clip(blankToNull(draft.summary()), 1000), ContentType.MARKDOWN, form,
                draft.markdown(), category.getId(), tags, null, draft.sourceUrl(),
                false, true, "", clip(blankToNull(draft.summary()), 500)));
        article.setTitleOrigin(TitleOrigin.MANUAL);
        article.setSummaryOrigin(SummaryOrigin.AI);
        article.setClassificationSource(ClassificationSource.AI);
        article.setClassificationStatus(ClassificationStatus.APPLIED);
        article.setClassificationConfidence(null);
        article.setClassificationReason("由分享对话生成并完成分类");
        article.setClassifiedContentHash(article.getContentHash());
        article.setClassifiedAt(Instant.now());
        article.setClassificationLocked(true);
        return article;
    }

    @Transactional
    public Article publish(Long id) {
        Article article = require(id);
        if (article.getStatus() == ArticleStatus.PUBLISHED) {
            saveRevision(article, "REPUBLISH");
        } else {
            saveRevision(article, "PUBLISH");
        }
        article.setStatus(ArticleStatus.PUBLISHED);
        if (article.getPublishedAt() == null) {
            article.setPublishedAt(Instant.now());
        }
        article.setDeletedAt(null);
        events.publishEvent(new ArticlePublishedEvent(article.getId(), article.getContentHash()));
        return article;
    }

    @Transactional
    public void withdraw(Long id) {
        Article article = require(id);
        article.setStatus(ArticleStatus.DRAFT);
    }

    @Transactional
    public void trash(Long id) {
        Article article = require(id);
        article.setStatus(ArticleStatus.TRASHED);
        article.setDeletedAt(Instant.now());
    }

    @Transactional
    public void restore(Long id) {
        Article article = require(id);
        article.setStatus(ArticleStatus.DRAFT);
        article.setDeletedAt(null);
    }

    @Transactional
    public void permanentlyDelete(Long id) {
        Article article = require(id);
        if (article.getStatus() != ArticleStatus.TRASHED) {
            throw new IllegalArgumentException("只有回收站中的文章可以永久删除");
        }
        articles.delete(article);
    }

    @Transactional(readOnly = true)
    public Article require(Long id) {
        return articles.findById(id).orElseThrow(() -> new IllegalArgumentException("文章不存在"));
    }

    @Transactional(readOnly = true)
    public Article requireDetailed(Long id) {
        return articles.findDetailedById(id).orElseThrow(() -> new IllegalArgumentException("文章不存在"));
    }

    @Transactional(readOnly = true)
    public ArticleCommand toCommand(Article article) {
        String tagNames = article.getTags().stream().map(tag -> tag.getName()).collect(java.util.stream.Collectors.joining(", "));
        String editableTitle = article.getTitleOrigin() == TitleOrigin.GENERATED ? "" : article.getTitle();
        ContentForm editableForm = article.isContentFormAutomatic() ? null : article.getContentForm();
        return new ArticleCommand(article.getId(), editableTitle, article.getSlug(), article.getSummary(),
                article.getContentType(), editableForm, article.getContent(), article.getCategory().getId(), tagNames,
                article.getSourceCitation(), article.getSourceUrl(),
                article.isPinned(), article.isClassificationLocked(), article.getSeoTitle(), article.getSeoDescription());
    }

    private String uniqueSlug(String requested) {
        return uniqueSlugFor(requested, null, null);
    }

    private void saveRevision(Article article, String reason) {
        revisions.saveAndFlush(new ArticleRevision(article, reason));
        List<ArticleRevision> history = revisions.findByArticleIdOrderByCreatedAtDesc(article.getId());
        if (history.size() > 20) {
            revisions.deleteAll(history.subList(20, history.size()));
        }
    }

    private boolean hasMeaningfulChange(Article article, ArticleCommand command, ContentForm contentForm,
            boolean automaticContentForm, String title) {
        return !Objects.equals(article.getTitle(), title)
                || !Objects.equals(article.getContent(), command.content())
                || article.getContentType() != command.contentType()
                || article.getContentForm() != contentForm
                || article.isContentFormAutomatic() != automaticContentForm;
    }

    private void applySummary(Article article, String submitted, String previous,
            SummaryOrigin previousOrigin, String plainContent, boolean isNew) {
        // 用户未改动自动摘要时重新从正文生成；手写摘要始终保留 MANUAL 所有权。
        if (submitted == null || (!isNew && previousOrigin == SummaryOrigin.GENERATED
                && Objects.equals(submitted, previous))) {
            article.setSummary(clip(plainContent, 240));
            article.setSummaryOrigin(SummaryOrigin.GENERATED);
        } else if (!isNew && Objects.equals(submitted, previous)) {
            article.setSummary(submitted);
            article.setSummaryOrigin(previousOrigin);
        } else {
            article.setSummary(submitted);
            article.setSummaryOrigin(SummaryOrigin.MANUAL);
        }
    }

    private String normalizeSourceUrl(String sourceUrl) {
        String value = blankToNull(sourceUrl);
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("来源链接必须是有效的 HTTP(S) 地址");
            }
            return uri.toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("来源链接必须是有效的 HTTP(S) 地址");
        }
    }

    private String generatedTitle(String plainContent) {
        String value = blankToNull(plainContent);
        return value == null ? "未命名内容" : clip(value, 32);
    }

    private String uniqueSlugFor(String requested, Long id, String fallback) {
        String base = requested == null ? "" : requested.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-_]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            if (fallback != null && !fallback.isBlank()) {
                return fallback;
            }
            base = "post-" + UUID.randomUUID().toString().substring(0, 8);
        }
        String candidate = base;
        while (id == null ? articles.existsBySlug(candidate) : articles.existsBySlugAndIdNot(candidate, id)) {
            candidate = base + "-" + UUID.randomUUID().toString().substring(0, 6);
        }
        return candidate;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…";
    }
}
