package beer.xiaoruru.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiJobRepository extends JpaRepository<AiJob, Long> {
    boolean existsByArticleIdAndContentHashAndStatusIn(Long articleId, String contentHash, List<AiJobStatus> statuses);

    Optional<AiJob> findFirstByArticleIdAndContentHashAndStatusIn(
            Long articleId, String contentHash, List<AiJobStatus> statuses);

    List<AiJob> findByStatus(AiJobStatus status);

    @EntityGraph(attributePaths = {"article", "article.category", "article.tags"})
    Optional<AiJob> findFirstByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(AiJobStatus status, Instant now);

    @EntityGraph(attributePaths = {"article"})
    List<AiJob> findTop50ByOrderByCreatedAtDesc();

    long countByStatus(AiJobStatus status);
}
