package beer.xiaoruru.similarity;

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

@Entity
@Table(name = "article_similarity_checks")
public class ArticleSimilarityCheck extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_article_id")
    private Article matchedArticle;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SimilarityRisk risk;
    @Column(name = "lexical_score", nullable = false)
    private double lexicalScore;
    @Column(name = "semantic_score")
    private Double semanticScore;
    @Column(name = "paragraph_coverage", nullable = false)
    private double paragraphCoverage;
    @Column(name = "semantic_coverage")
    private Double semanticCoverage;
    @Column(name = "embedding_compared", nullable = false)
    private int embeddingCompared;
    @Column(name = "embedding_candidates", nullable = false)
    private int embeddingCandidates;
    @Column(name = "matched_excerpt", length = 1000)
    private String matchedExcerpt;

    protected ArticleSimilarityCheck() {}

    ArticleSimilarityCheck(Article article, String contentHash, Article matchedArticle,
            SimilarityRisk risk, double lexicalScore, Double semanticScore, double paragraphCoverage,
            Double semanticCoverage, int embeddingCompared, int embeddingCandidates, String matchedExcerpt) {
        this.article = article;
        this.contentHash = contentHash;
        this.matchedArticle = matchedArticle;
        this.risk = risk;
        this.lexicalScore = lexicalScore;
        this.semanticScore = semanticScore;
        this.paragraphCoverage = paragraphCoverage;
        this.semanticCoverage = semanticCoverage;
        this.embeddingCompared = embeddingCompared;
        this.embeddingCandidates = embeddingCandidates;
        this.matchedExcerpt = matchedExcerpt;
    }

    public Article getArticle() { return article; }
    public String getContentHash() { return contentHash; }
    public Article getMatchedArticle() { return matchedArticle; }
    public SimilarityRisk getRisk() { return risk; }
    public double getLexicalScore() { return lexicalScore; }
    public Double getSemanticScore() { return semanticScore; }
    public double getParagraphCoverage() { return paragraphCoverage; }
    public Double getSemanticCoverage() { return semanticCoverage; }
    public int getEmbeddingCompared() { return embeddingCompared; }
    public int getEmbeddingCandidates() { return embeddingCandidates; }
    public String getMatchedExcerpt() { return matchedExcerpt; }
    public boolean isCurrent() { return article != null && contentHash.equals(article.getContentHash()); }
}
