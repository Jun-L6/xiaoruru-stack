package beer.xiaoruru.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationImportRepository extends JpaRepository<ConversationImport, Long> {
    Optional<ConversationImport> findFirstByStatusOrderByCreatedAtAsc(ConversationImportStatus status);
    List<ConversationImport> findByStatus(ConversationImportStatus status);

    @EntityGraph(attributePaths = "article")
    List<ConversationImport> findTop50ByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = "article")
    Optional<ConversationImport> findDetailedById(Long id);
}
