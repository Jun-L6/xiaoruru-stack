package beer.xiaoruru.article;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    @EntityGraph(attributePaths = {"category", "category.parent", "tags"})
    Page<Article> findByStatusAndKind(ArticleStatus status, ArticleKind kind, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "category.parent", "tags"})
    Optional<Article> findBySlugAndStatus(String slug, ArticleStatus status);

    @EntityGraph(attributePaths = {"category", "category.parent", "tags"})
    @Query("select distinct a from Article a where a.id = :id")
    Optional<Article> findDetailedById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"category", "category.parent", "tags"})
    @Query("select distinct a from Article a where a.status = beer.xiaoruru.article.ArticleStatus.PUBLISHED "
            + "and a.kind = beer.xiaoruru.article.ArticleKind.POST and a.category.slug = :slug")
    Page<Article> findPublishedByCategorySlug(@Param("slug") String slug, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "category.parent", "tags"})
    @Query("select distinct a from Article a join a.tags t where a.status = beer.xiaoruru.article.ArticleStatus.PUBLISHED "
            + "and a.kind = beer.xiaoruru.article.ArticleKind.POST and t.slug = :slug")
    Page<Article> findPublishedByTagSlug(@Param("slug") String slug, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "category.parent", "tags"})
    @Query("select distinct a from Article a left join a.tags t where "
            + "a.status = beer.xiaoruru.article.ArticleStatus.PUBLISHED "
            + "and a.kind = beer.xiaoruru.article.ArticleKind.POST and ("
            + "lower(a.title) like lower(concat('%', :q, '%')) or "
            + "lower(coalesce(a.summary, '')) like lower(concat('%', :q, '%')) or "
            + "lower(a.content) like lower(concat('%', :q, '%')) or "
            + "lower(a.category.name) like lower(concat('%', :q, '%')) or "
            + "lower(t.name) like lower(concat('%', :q, '%'))) ")
    List<Article> searchPublished(@Param("q") String query);

    @EntityGraph(attributePaths = {"category", "category.parent", "tags"})
    @Query("select distinct a from Article a where a.status = beer.xiaoruru.article.ArticleStatus.PUBLISHED "
            + "and a.kind = beer.xiaoruru.article.ArticleKind.POST order by a.publishedAt desc")
    List<Article> findAllPublishedForArchive();

    @Query("select a from Article a where a.status = beer.xiaoruru.article.ArticleStatus.PUBLISHED "
            + "and a.kind = beer.xiaoruru.article.ArticleKind.POST order by a.id")
    List<Article> findAllPublishedForSimilarity();

    @EntityGraph(attributePaths = {"category", "category.parent", "tags"})
    @Query("select distinct a from Article a order by a.updatedAt desc")
    List<Article> findAllForAdmin();

    @EntityGraph(attributePaths = {"category", "category.parent", "tags"})
    @Query("select distinct a from Article a left join a.tags t where "
            + "(:q = '' or lower(a.title) like lower(concat('%', :q, '%')) "
            + "or lower(coalesce(a.summary, '')) like lower(concat('%', :q, '%'))) "
            + "and (:status is null or a.status = :status) "
            + "and (:contentType is null or a.contentType = :contentType) "
            + "and (:contentForm is null or a.contentForm = :contentForm) "
            + "and (:categoryId is null or a.category.id = :categoryId) "
            + "and (:tagId is null or t.id = :tagId) "
            + "and (:classificationStatus is null or a.classificationStatus = :classificationStatus) "
            + "and (:publishedFrom is null or a.publishedAt >= :publishedFrom) "
            + "and (:publishedUntil is null or a.publishedAt < :publishedUntil) "
            + "order by a.updatedAt desc")
    List<Article> filterForAdmin(@Param("q") String query,
            @Param("status") ArticleStatus status,
            @Param("contentType") ContentType contentType,
            @Param("contentForm") ContentForm contentForm,
            @Param("categoryId") Long categoryId,
            @Param("tagId") Long tagId,
            @Param("classificationStatus") ClassificationStatus classificationStatus,
            @Param("publishedFrom") Instant publishedFrom,
            @Param("publishedUntil") Instant publishedUntil);

    long countByStatus(ArticleStatus status);

    long countByClassificationStatus(ClassificationStatus status);

    boolean existsBySlugAndIdNot(String slug, Long id);

    boolean existsBySlug(String slug);

    long countByCategoryId(Long categoryId);

    long countByCategoryIdAndStatusAndKind(Long categoryId, ArticleStatus status, ArticleKind kind);

    long countByTagsId(Long tagId);

    long countByTagsIdAndStatusAndKind(Long tagId, ArticleStatus status, ArticleKind kind);

    @EntityGraph(attributePaths = "tags")
    @Query("select distinct a from Article a join a.tags t where t.id = :tagId")
    List<Article> findAllByTagId(@Param("tagId") Long tagId);

    Optional<Article> findFirstByStatusAndKindAndPublishedAtLessThanOrderByPublishedAtDesc(
            ArticleStatus status, ArticleKind kind, Instant publishedAt);

    Optional<Article> findFirstByStatusAndKindAndPublishedAtGreaterThanOrderByPublishedAtAsc(
            ArticleStatus status, ArticleKind kind, Instant publishedAt);

    boolean existsByContentContaining(String fragment);

    @EntityGraph(attributePaths = {"category", "category.parent", "tags"})
    List<Article> findAllByContentContaining(String fragment);
}
