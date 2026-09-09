package beer.xiaoruru.similarity;

import beer.xiaoruru.ai.AiSettingsService;
import beer.xiaoruru.article.Article;
import beer.xiaoruru.article.ArticleRepository;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ArticleSimilarityService {
    private static final Logger log = LoggerFactory.getLogger(ArticleSimilarityService.class);
    private static final int MAX_INDEXED_CANDIDATES_PER_CHECK = 20;
    private static final int MAX_EMBEDDING_CHUNKS = 24;
    private static final int CHUNK_CHARACTERS = 1_200;

    private final ArticleRepository articles;
    private final ArticleSimilarityIndexRepository indexes;
    private final ArticleSimilarityCheckRepository checks;
    private final AiSettingsService settings;
    private final EmbeddingGateway embeddings;

    public ArticleSimilarityService(ArticleRepository articles, ArticleSimilarityIndexRepository indexes,
            ArticleSimilarityCheckRepository checks, AiSettingsService settings, EmbeddingGateway embeddings) {
        this.articles = articles;
        this.indexes = indexes;
        this.checks = checks;
        this.settings = settings;
        this.embeddings = embeddings;
    }

    public ArticleSimilarityCheck check(Long articleId) {
        Article source = articles.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("文章不存在"));
        List<Article> candidates = articles.findAllPublishedForSimilarity().stream()
                .filter(article -> !article.getId().equals(source.getId())).toList();
        TextSimilarity.Document sourceText = TextSimilarity.document(source.getContent());
        Map<Long, TextSimilarity.Score> lexical = new LinkedHashMap<>();
        for (Article candidate : candidates) {
            lexical.put(candidate.getId(), TextSimilarity.compare(sourceText,
                    TextSimilarity.document(candidate.getContent())));
        }

        SemanticContext semantic = settings.embeddingEnabled()
                ? semantic(source, candidates) : SemanticContext.disabled(candidates.size());
        Candidate best = null;
        for (Article candidate : candidates) {
            TextSimilarity.Score text = lexical.get(candidate.getId());
            SemanticScore vector = semantic.scores().get(candidate.getId());
            SimilarityRisk risk = risk(sourceText.normalizedHash(),
                    TextSimilarity.document(candidate.getContent()).normalizedHash(), text, vector);
            Candidate scored = new Candidate(candidate, text, vector, risk);
            if (best == null || scored.rank() > best.rank()
                    || (scored.rank() == best.rank() && scored.combined() > best.combined())) {
                best = scored;
            }
        }
        if (best == null) {
            return checks.save(new ArticleSimilarityCheck(source, source.getContentHash(), null,
                    SimilarityRisk.NONE, 0, null, 0, null,
                    semantic.compared(), candidates.size(), null));
        }
        return checks.save(new ArticleSimilarityCheck(source, source.getContentHash(), best.article(),
                best.risk(), best.text().global(), best.semantic() == null ? null : best.semantic().documentScore(),
                best.text().coverage(), best.semantic() == null ? null : best.semantic().coverage(),
                semantic.compared(), candidates.size(), best.text().matchedExcerpt()));
    }

    public Optional<ArticleSimilarityCheck> latest(Long articleId) {
        return checks.findFirstByArticleIdOrderByCreatedAtDesc(articleId);
    }

    public int indexNextPublished(int limit) {
        if (!settings.embeddingEnabled()) throw new IllegalStateException("请先启用语义相似检测。");
        AiSettingsService.EmbeddingConnection connection = settings.embeddingConnection();
        List<Article> missing = articles.findAllPublishedForSimilarity().stream()
                .filter(article -> !isCurrent(indexes.findByArticleId(article.getId()).orElse(null), article, connection.model()))
                .limit(Math.max(1, Math.min(limit, 100))).toList();
        index(missing, connection);
        return missing.size();
    }

    private SemanticContext semantic(Article source, List<Article> candidates) {
        try {
            AiSettingsService.EmbeddingConnection connection = settings.embeddingConnection();
            List<Article> toIndex = new ArrayList<>();
            if (!isCurrent(indexes.findByArticleId(source.getId()).orElse(null), source, connection.model())) {
                toIndex.add(source);
            }
            candidates.stream()
                    .filter(candidate -> !isCurrent(indexes.findByArticleId(candidate.getId()).orElse(null),
                            candidate, connection.model()))
                    .limit(MAX_INDEXED_CANDIDATES_PER_CHECK)
                    .forEach(toIndex::add);
            index(toIndex, connection);
            ArticleSimilarityIndex sourceIndex = indexes.findByArticleId(source.getId()).orElse(null);
            if (!isCurrent(sourceIndex, source, connection.model())) return SemanticContext.disabled(candidates.size());
            VectorBundle sourceVectors = decode(sourceIndex);
            Map<Long, SemanticScore> scores = new LinkedHashMap<>();
            for (Article candidate : candidates) {
                ArticleSimilarityIndex candidateIndex = indexes.findByArticleId(candidate.getId()).orElse(null);
                if (!isCurrent(candidateIndex, candidate, connection.model())) continue;
                scores.put(candidate.getId(), compare(sourceVectors, decode(candidateIndex)));
            }
            return new SemanticContext(scores, scores.size(), candidates.size());
        } catch (RuntimeException exception) {
            log.warn("Semantic similarity is temporarily unavailable: {}", exception.getMessage());
            return SemanticContext.disabled(candidates.size());
        }
    }

    private void index(List<Article> input, AiSettingsService.EmbeddingConnection connection) {
        if (input.isEmpty()) return;
        List<ArticleChunks> articlesAndChunks = input.stream()
                .map(article -> new ArticleChunks(article, chunks(article))).toList();
        List<String> flat = articlesAndChunks.stream().flatMap(item -> item.chunks().stream()).toList();
        List<float[]> vectors = embeddings.embed(connection, flat);
        int offset = 0;
        for (ArticleChunks item : articlesAndChunks) {
            List<float[]> articleVectors = new ArrayList<>(vectors.subList(offset, offset + item.chunks().size()));
            offset += item.chunks().size();
            float[] average = average(articleVectors);
            articleVectors.add(0, average);
            ArticleSimilarityIndex index = indexes.findByArticleId(item.article().getId())
                    .orElseGet(() -> new ArticleSimilarityIndex(item.article()));
            index.setContentHash(item.article().getContentHash());
            index.setNormalizedHash(TextSimilarity.document(item.article().getContent()).normalizedHash());
            index.setEmbeddingModel(connection.model());
            index.setEmbeddingDimensions(average.length);
            index.setEmbeddingChunkCount(item.chunks().size());
            index.setEmbeddingVectors(encode(articleVectors));
            indexes.save(index);
        }
    }

    private List<String> chunks(Article article) {
        TextSimilarity.Document document = TextSimilarity.document(article.getContent());
        String content = document.normalized();
        if (content.isBlank()) content = article.getTitle();
        List<String> result = new ArrayList<>();
        for (int start = 0; start < content.length() && result.size() < MAX_EMBEDDING_CHUNKS; start += CHUNK_CHARACTERS) {
            result.add(content.substring(start, Math.min(content.length(), start + CHUNK_CHARACTERS)));
        }
        if (result.isEmpty()) result.add(article.getTitle());
        return result;
    }

    private SimilarityRisk risk(String sourceHash, String candidateHash, TextSimilarity.Score text,
            SemanticScore semantic) {
        if (!sourceHash.isBlank() && sourceHash.equals(candidateHash)) return SimilarityRisk.EXACT;
        if (text.global() >= 0.42 || (text.global() >= 0.22 && text.coverage() >= 0.65)) {
            return SimilarityRisk.HIGH;
        }
        if (semantic != null && semantic.documentScore() >= 0.90 && semantic.coverage() >= 0.50) {
            return SimilarityRisk.HIGH;
        }
        if (semantic != null && text.global() >= 0.22 && semantic.documentScore() >= 0.85) {
            return SimilarityRisk.HIGH;
        }
        if (text.global() >= 0.22 || text.coverage() >= 0.40
                || semantic != null && (semantic.documentScore() >= 0.82 || semantic.coverage() >= 0.30)) {
            return SimilarityRisk.RELATED;
        }
        return SimilarityRisk.NONE;
    }

    private SemanticScore compare(VectorBundle source, VectorBundle candidate) {
        double document = cosine(source.vectors().get(0), candidate.vectors().get(0));
        int matched = 0;
        List<float[]> candidateChunks = candidate.vectors().subList(1, candidate.vectors().size());
        for (float[] sourceChunk : source.vectors().subList(1, source.vectors().size())) {
            double best = candidateChunks.stream().mapToDouble(target -> cosine(sourceChunk, target)).max().orElse(0);
            if (best >= 0.88) matched++;
        }
        double coverage = source.vectors().size() <= 1 ? 0 : (double) matched / (source.vectors().size() - 1);
        return new SemanticScore(document, coverage);
    }

    private boolean isCurrent(ArticleSimilarityIndex index, Article article, String model) {
        return index != null && article.getContentHash().equals(index.getContentHash())
                && model.equals(index.getEmbeddingModel()) && index.getEmbeddingVectors() != null
                && index.getEmbeddingDimensions() != null && index.getEmbeddingChunkCount() != null;
    }

    private byte[] encode(List<float[]> vectors) {
        int dimensions = vectors.get(0).length;
        ByteBuffer buffer = ByteBuffer.allocate(vectors.size() * dimensions * Float.BYTES);
        vectors.forEach(vector -> { for (float value : vector) buffer.putFloat(value); });
        return buffer.array();
    }

    private VectorBundle decode(ArticleSimilarityIndex index) {
        int dimensions = index.getEmbeddingDimensions();
        int count = index.getEmbeddingChunkCount() + 1;
        ByteBuffer buffer = ByteBuffer.wrap(index.getEmbeddingVectors());
        if (buffer.remaining() != count * dimensions * Float.BYTES) {
            throw new IllegalStateException("本地语义索引损坏，请重新生成。");
        }
        List<float[]> vectors = new ArrayList<>();
        for (int row = 0; row < count; row++) {
            float[] vector = new float[dimensions];
            for (int i = 0; i < dimensions; i++) vector[i] = buffer.getFloat();
            vectors.add(vector);
        }
        return new VectorBundle(vectors);
    }

    private float[] average(List<float[]> vectors) {
        float[] result = new float[vectors.get(0).length];
        for (float[] vector : vectors) for (int i = 0; i < result.length; i++) result[i] += vector[i];
        for (int i = 0; i < result.length; i++) result[i] /= vectors.size();
        return result;
    }

    private double cosine(float[] left, float[] right) {
        if (left.length != right.length) return 0;
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private record ArticleChunks(Article article, List<String> chunks) {}
    private record VectorBundle(List<float[]> vectors) {}
    private record SemanticScore(double documentScore, double coverage) {}
    private record SemanticContext(Map<Long, SemanticScore> scores, int compared, int candidates) {
        static SemanticContext disabled(int candidates) { return new SemanticContext(Map.of(), 0, candidates); }
    }
    private record Candidate(Article article, TextSimilarity.Score text, SemanticScore semantic, SimilarityRisk risk) {
        int rank() { return switch (risk) { case NONE -> 0; case RELATED -> 1; case HIGH -> 2; case EXACT -> 3; }; }
        double combined() { return text.global() + text.coverage()
                + (semantic == null ? 0 : semantic.documentScore() + semantic.coverage()); }
    }
}
