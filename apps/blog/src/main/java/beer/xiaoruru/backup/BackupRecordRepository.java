package beer.xiaoruru.backup;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BackupRecordRepository extends JpaRepository<BackupRecord, Long> {
    List<BackupRecord> findAllByOrderByCreatedAtDesc();
    List<BackupRecord> findByTypeAndStatusOrderByCreatedAtDesc(BackupType type, BackupStatus status);
    List<BackupRecord> findByStatus(BackupStatus status);
    Optional<BackupRecord> findFirstByStatusOrderByCreatedAtDesc(BackupStatus status);
}
