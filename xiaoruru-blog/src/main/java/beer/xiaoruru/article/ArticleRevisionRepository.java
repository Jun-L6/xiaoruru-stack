package beer.xiaoruru.article;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleRevisionRepository extends JpaRepository<ArticleRevision, Long> {
    List<ArticleRevision> findByArticleIdOrderByCreatedAtDesc(Long articleId);
}
