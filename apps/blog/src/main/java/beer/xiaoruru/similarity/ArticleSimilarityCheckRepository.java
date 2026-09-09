package beer.xiaoruru.similarity;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface ArticleSimilarityCheckRepository extends JpaRepository<ArticleSimilarityCheck, Long> {
    @EntityGraph(attributePaths = {"article", "matchedArticle"})
    Optional<ArticleSimilarityCheck> findFirstByArticleIdOrderByCreatedAtDesc(Long articleId);
}
