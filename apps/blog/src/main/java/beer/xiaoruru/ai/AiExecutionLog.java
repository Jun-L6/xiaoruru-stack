package beer.xiaoruru.ai;

import beer.xiaoruru.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_execution_logs")
public class AiExecutionLog extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private AiJob job;
    @Column(nullable = false, length = 120)
    private String model;
    @Column(name = "endpoint_identifier", nullable = false, length = 200)
    private String endpointIdentifier;
    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs;
    @Column(name = "input_tokens")
    private Long inputTokens;
    @Column(name = "output_tokens")
    private Long outputTokens;
    @Column(name = "parse_status", nullable = false, length = 30)
    private String parseStatus;
    @Column(name = "category_slug", length = 100)
    private String categorySlug;
    @Column(name = "content_form", length = 20)
    private String contentForm;
    private Double confidence;
    @Column(name = "result_tags", length = 500)
    private String resultTags;
    @Column(name = "suggested_category", length = 200)
    private String suggestedCategory;
    @Column(name = "error_type", length = 80)
    private String errorType;
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    protected AiExecutionLog() {}

    public AiExecutionLog(AiJob job, String model, String endpointIdentifier, long elapsedMs) {
        this.job = job;
        this.model = model;
        this.endpointIdentifier = endpointIdentifier;
        this.elapsedMs = elapsedMs;
    }

    public AiJob getJob() { return job; }
    public String getModel() { return model; }
    public String getEndpointIdentifier() { return endpointIdentifier; }
    public long getElapsedMs() { return elapsedMs; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }
    public String getCategorySlug() { return categorySlug; }
    public void setCategorySlug(String categorySlug) { this.categorySlug = categorySlug; }
    public String getContentForm() { return contentForm; }
    public void setContentForm(String contentForm) { this.contentForm = contentForm; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getResultTags() { return resultTags; }
    public void setResultTags(String resultTags) { this.resultTags = resultTags; }
    public String getSuggestedCategory() { return suggestedCategory; }
    public void setSuggestedCategory(String suggestedCategory) { this.suggestedCategory = suggestedCategory; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
