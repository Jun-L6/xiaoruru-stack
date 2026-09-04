package beer.xiaoruru.media;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {
    List<MediaAsset> findByDeletedFalseOrderByCreatedAtDesc();
    Optional<MediaAsset> findByIdAndDeletedFalse(Long id);
}
