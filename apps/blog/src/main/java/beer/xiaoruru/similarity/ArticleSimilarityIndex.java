package beer.xiaoruru.similarity;

import beer.xiaoruru.article.Article;
import beer.xiaoruru.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "article_similarity_indexes")
class ArticleSimilarityIndex extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false, unique = true)
    private Article article;
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;
    @Column(name = "normalized_hash", nullable = false, length = 64)
    private String normalizedHash;
    @Column(name = "embedding_model", length = 200)
    private String embeddingModel;
    @Column(name = "embedding_dimensions")
    private Integer embeddingDimensions;
    @Column(name = "embedding_chunk_count")
    private Integer embeddingChunkCount;
    @Lob
    @Column(name = "embedding_vectors")
    private byte[] embeddingVectors;

    protected ArticleSimilarityIndex() {}

    ArticleSimilarityIndex(Article article) { this.article = article; }

    Article getArticle() { return article; }
    String getContentHash() { return contentHash; }
    void setContentHash(String contentHash) { this.contentHash = contentHash; }
    String getNormalizedHash() { return normalizedHash; }
    void setNormalizedHash(String normalizedHash) { this.normalizedHash = normalizedHash; }
    String getEmbeddingModel() { return embeddingModel; }
    void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    Integer getEmbeddingDimensions() { return embeddingDimensions; }
    void setEmbeddingDimensions(Integer embeddingDimensions) { this.embeddingDimensions = embeddingDimensions; }
    Integer getEmbeddingChunkCount() { return embeddingChunkCount; }
    void setEmbeddingChunkCount(Integer embeddingChunkCount) { this.embeddingChunkCount = embeddingChunkCount; }
    byte[] getEmbeddingVectors() { return embeddingVectors; }
    void setEmbeddingVectors(byte[] embeddingVectors) { this.embeddingVectors = embeddingVectors; }
}
