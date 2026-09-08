package beer.xiaoruru.article;

import beer.xiaoruru.common.BaseEntity;
import beer.xiaoruru.taxonomy.Category;
import beer.xiaoruru.taxonomy.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 文章聚合根。
 *
 * <p>除正文和发布状态外，它还保存内容形态、渲染快照以及 AI 分类状态。
 * 这些数据共同决定管理端如何编辑、前台如何展示以及 AI 结果是否可以覆盖当前值。
 */
@Entity
@Table(name = "articles", uniqueConstraints = @UniqueConstraint(name = "uk_article_slug", columnNames = "slug"))
public class Article extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ArticleKind kind = ArticleKind.POST;

    @Column(nullable = false, length = 200)
    private String title;

    /** 用于区分作者标题与仅供后台、搜索使用的内部标题。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "title_origin", nullable = false, length = 20)
    private TitleOrigin titleOrigin = TitleOrigin.MANUAL;

    @Column(nullable = false, length = 220)
    private String slug;

    @Column(length = 1000)
    private String summary;

    /** 摘要来源决定后续 AI 是否允许替换该摘要。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "summary_origin", nullable = false, length = 20)
    private SummaryOrigin summaryOrigin = SummaryOrigin.GENERATED;

    @Column(nullable = false, columnDefinition = "CLOB")
    private String content = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private ContentType contentType = ContentType.MARKDOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_form", nullable = false, length = 20)
    private ContentForm contentForm = ContentForm.LONGFORM;

    /** true 表示内容形态仍由 AI 管理；false 表示作者已手动指定。 */
    @Column(name = "content_form_automatic", nullable = false)
    private boolean contentFormAutomatic = true;

    /** 摘录的作者、书名或其他人类可读出处。 */
    @Column(name = "source_citation", length = 300)
    private String sourceCitation;

    /** 摘录的可选 HTTP(S) 来源地址。 */
    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    /** 原始正文的 SHA-256，用于识别过期 AI 任务和分类结果。 */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** 保存时已完成安全过滤的 HTML，前台不再重复解析正文。 */
    @Column(name = "rendered_html", columnDefinition = "CLOB")
    private String renderedHtml;

    @Column(name = "render_version", nullable = false)
    private int renderVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ArticleStatus status = ArticleStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "article_tags",
            joinColumns = @JoinColumn(name = "article_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new LinkedHashSet<>();

    @Column(nullable = false)
    private boolean pinned;

    @Column(name = "seo_title", length = 200)
    private String seoTitle;

    @Column(name = "seo_description", length = 500)
    private String seoDescription;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification_source", nullable = false, length = 20)
    private ClassificationSource classificationSource = ClassificationSource.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification_status", nullable = false, length = 20)
    private ClassificationStatus classificationStatus = ClassificationStatus.PENDING;

    @Column(name = "classification_confidence")
    private Double classificationConfidence;

    @Column(name = "classification_reason", length = 1000)
    private String classificationReason;

    /** 人工分类或人工内容形态会锁定结果，防止延迟到达的 AI 结果覆盖它。 */
    @Column(name = "classification_locked", nullable = false)
    private boolean classificationLocked;

    /** 生成当前分类结果时的正文哈希。 */
    @Column(name = "classified_content_hash", length = 64)
    private String classifiedContentHash;

    @Column(name = "classified_at")
    private Instant classifiedAt;

    protected Article() {}

    public Article(String title, String slug, Category category) {
        this.title = title;
        this.slug = slug;
        this.category = category;
    }

    public ArticleKind getKind() { return kind; }
    public void setKind(ArticleKind kind) { this.kind = kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public TitleOrigin getTitleOrigin() { return titleOrigin; }
    public void setTitleOrigin(TitleOrigin titleOrigin) { this.titleOrigin = titleOrigin; }
    /** 内部生成的动态/摘录标题不在前台正文区显示。 */
    public boolean isTitleDisplayed() { return titleOrigin == TitleOrigin.MANUAL || !contentForm.isTitleOptional(); }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public SummaryOrigin getSummaryOrigin() { return summaryOrigin; }
    public void setSummaryOrigin(SummaryOrigin summaryOrigin) { this.summaryOrigin = summaryOrigin; }
    public boolean isManualSummary() { return summaryOrigin == SummaryOrigin.MANUAL; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public ContentType getContentType() { return contentType; }
    public void setContentType(ContentType contentType) { this.contentType = contentType; }
    public ContentForm getContentForm() { return contentForm; }
    public void setContentForm(ContentForm contentForm) { this.contentForm = contentForm; }
    public boolean isContentFormAutomatic() { return contentFormAutomatic; }
    public void setContentFormAutomatic(boolean contentFormAutomatic) { this.contentFormAutomatic = contentFormAutomatic; }
    public String getSourceCitation() { return sourceCitation; }
    public void setSourceCitation(String sourceCitation) { this.sourceCitation = sourceCitation; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getRenderedHtml() { return renderedHtml; }
    public void setRenderedHtml(String renderedHtml) { this.renderedHtml = renderedHtml; }
    public int getRenderVersion() { return renderVersion; }
    public void setRenderVersion(int renderVersion) { this.renderVersion = renderVersion; }
    public ArticleStatus getStatus() { return status; }
    public void setStatus(ArticleStatus status) { this.status = status; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public Set<Tag> getTags() { return tags; }
    public void setTags(Set<Tag> tags) { this.tags = tags; }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public String getSeoTitle() { return seoTitle; }
    public void setSeoTitle(String seoTitle) { this.seoTitle = seoTitle; }
    public String getSeoDescription() { return seoDescription; }
    public void setSeoDescription(String seoDescription) { this.seoDescription = seoDescription; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public ClassificationSource getClassificationSource() { return classificationSource; }
    public void setClassificationSource(ClassificationSource classificationSource) { this.classificationSource = classificationSource; }
    public ClassificationStatus getClassificationStatus() { return classificationStatus; }
    public void setClassificationStatus(ClassificationStatus classificationStatus) { this.classificationStatus = classificationStatus; }
    public Double getClassificationConfidence() { return classificationConfidence; }
    public void setClassificationConfidence(Double classificationConfidence) { this.classificationConfidence = classificationConfidence; }
    public String getClassificationReason() { return classificationReason; }
    public void setClassificationReason(String classificationReason) { this.classificationReason = classificationReason; }
    public boolean isClassificationLocked() { return classificationLocked; }
    public void setClassificationLocked(boolean classificationLocked) { this.classificationLocked = classificationLocked; }
    public String getClassifiedContentHash() { return classifiedContentHash; }
    public void setClassifiedContentHash(String classifiedContentHash) { this.classifiedContentHash = classifiedContentHash; }
    public Instant getClassifiedAt() { return classifiedAt; }
    public void setClassifiedAt(Instant classifiedAt) { this.classifiedAt = classifiedAt; }
}
