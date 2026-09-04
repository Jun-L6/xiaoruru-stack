package beer.xiaoruru.taxonomy;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    @EntityGraph(attributePaths = "parent")
    List<Category> findAllByOrderBySortOrderAscNameAsc();

    @EntityGraph(attributePaths = "parent")
    List<Category> findByEnabledTrueOrderBySortOrderAscNameAsc();

    @EntityGraph(attributePaths = "parent")
    Optional<Category> findBySlug(String slug);

    @Query("select c from Category c left join fetch c.parent where c.enabled = true "
            + "and (c.parent is null or c.parent.enabled = true) "
            + "and not exists (select child.id from Category child where child.parent = c) "
            + "order by c.sortOrder, c.name")
    List<Category> findEnabledLeaves();

    boolean existsByParentId(Long parentId);
    boolean existsBySlug(String slug);
}
