package beer.xiaoruru.ai;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiExecutionLogRepository extends JpaRepository<AiExecutionLog, Long> {
    @EntityGraph(attributePaths = {"job", "job.article"})
    List<AiExecutionLog> findTop100ByOrderByCreatedAtDesc();
}
