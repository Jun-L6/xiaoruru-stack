package beer.xiaoruru.article;

import beer.xiaoruru.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "article_revisions")
public class ArticleRevision extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "CLOB", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private ContentType contentType;

    @Column(nullable = false, length = 30)
    private String reason;

    protected ArticleRevision() {}

    public ArticleRevision(Article article, String reason) {
        this.article = article;
        this.title = article.getTitle();
        this.content = article.getContent();
        this.contentType = article.getContentType();
        this.reason = reason;
    }
}
