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

@Entity
@Table(name = "articles", uniqueConstraints = @UniqueConstraint(name = "uk_article_slug", columnNames = "slug"))
public class Article extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ArticleKind kind = ArticleKind.POST;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 220)
    private String slug;

    @Column(length = 1000)
    private String summary;

    @Column(nullable = false, columnDefinition = "CLOB")
    private String content = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private ContentType contentType = ContentType.MARKDOWN;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

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

    @Column(name = "classification_locked", nullable = false)
    private boolean classificationLocked;

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
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public ContentType getContentType() { return contentType; }
    public void setContentType(ContentType contentType) { this.contentType = contentType; }
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
