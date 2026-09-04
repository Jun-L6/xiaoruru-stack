package beer.xiaoruru.taxonomy;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByNormalizedName(String normalizedName);
    Optional<Tag> findBySlug(String slug);
    boolean existsBySlug(String slug);

    @Query("select t from Tag t order by t.name")
    List<Tag> findAllSorted();
}
