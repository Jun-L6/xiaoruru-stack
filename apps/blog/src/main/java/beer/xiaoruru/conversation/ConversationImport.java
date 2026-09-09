package beer.xiaoruru.conversation;

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
@Table(name = "conversation_imports")
public class ConversationImport extends BaseEntity {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationProvider provider;
    @Column(name = "source_url", nullable = false, length = 1000)
    private String sourceUrl;
    @Column(name = "source_title", length = 300)
    private String sourceTitle;
    @Column(nullable = false, columnDefinition = "CLOB")
    private String snapshot;
    @Column(name = "message_count", nullable = false)
    private int messageCount;
    @Column(name = "question_answer_count", nullable = false)
    private int questionAnswerCount;
    @Column(name = "character_count", nullable = false)
    private int characterCount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationImportStatus status = ConversationImportStatus.EXTRACTED;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    private Article article;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "error_type", length = 80)
    private String errorType;
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    protected ConversationImport() {}

    public ConversationImport(String sourceUrl, ConversationSnapshot snapshot, String serialized) {
        this.sourceUrl = sourceUrl;
        this.provider = snapshot.provider();
        this.sourceTitle = snapshot.sourceTitle();
        this.snapshot = serialized;
        this.messageCount = snapshot.messages().size();
        this.questionAnswerCount = snapshot.questionAnswerCount();
        this.characterCount = snapshot.characterCount();
    }

    public ConversationProvider getProvider() { return provider; }
    public String getSourceUrl() { return sourceUrl; }
    public String getSourceTitle() { return sourceTitle; }
    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
    public int getMessageCount() { return messageCount; }
    public int getQuestionAnswerCount() { return questionAnswerCount; }
    public int getCharacterCount() { return characterCount; }
    public ConversationImportStatus getStatus() { return status; }
    public void setStatus(ConversationImportStatus status) { this.status = status; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public Article getArticle() { return article; }
    public void setArticle(Article article) { this.article = article; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
