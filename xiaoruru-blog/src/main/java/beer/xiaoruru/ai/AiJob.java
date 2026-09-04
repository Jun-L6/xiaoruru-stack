package beer.xiaoruru.ai;

import beer.xiaoruru.article.Article;
import beer.xiaoruru.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_jobs")
public class AiJob extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiJobStatus status = AiJobStatus.PENDING;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "available_at", nullable = false)
    private Instant availableAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "error_type", length = 80)
    private String errorType;
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    protected AiJob() {}

    public AiJob(Article article, String contentHash, Instant availableAt) {
        this.article = article;
        this.contentHash = contentHash;
        this.availableAt = availableAt;
    }

    public Article getArticle() { return article; }
    public String getContentHash() { return contentHash; }
    public AiJobStatus getStatus() { return status; }
    public void setStatus(AiJobStatus status) { this.status = status; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public Instant getAvailableAt() { return availableAt; }
    public void setAvailableAt(Instant availableAt) { this.availableAt = availableAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
