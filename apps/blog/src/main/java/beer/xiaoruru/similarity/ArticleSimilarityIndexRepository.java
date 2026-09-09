package beer.xiaoruru.similarity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ArticleSimilarityIndexRepository extends JpaRepository<ArticleSimilarityIndex, Long> {
    Optional<ArticleSimilarityIndex> findByArticleId(Long articleId);
}
