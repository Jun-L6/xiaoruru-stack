package beer.xiaoruru.taxonomy;

import beer.xiaoruru.article.Article;
import beer.xiaoruru.article.ArticleRepository;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分类树和标签的业务约束边界。
 *
 * <p>文章只能关联启用的叶子分类，分类树最多两级。
 * 标签经 NFKC 和小写化后去重，但保留首次创建时的展示名称。
 */
@Service
public class TaxonomyService {
    private final CategoryRepository categories;
    private final TagRepository tags;
    private final ArticleRepository articles;

    public TaxonomyService(CategoryRepository categories, TagRepository tags, ArticleRepository articles) {
        this.categories = categories;
        this.tags = tags;
        this.articles = articles;
    }

    @Transactional(readOnly = true)
    public Category requireLeaf(Long id) {
        Category category = categories.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分类不存在"));
        if (!category.isEnabled() || categories.existsByParentId(category.getId())) {
            throw new IllegalArgumentException("文章必须选择启用的叶子分类");
        }
        return category;
    }

    @Transactional(readOnly = true)
    public Category requireLeafBySlug(String slug) {
        Category category = categories.findBySlug(slug == null ? "" : slug.strip())
                .orElseGet(() -> categories.findBySlug("uncategorized").orElseThrow());
        if (!category.isEnabled() || categories.existsByParentId(category.getId())) {
            return categories.findBySlug("uncategorized").orElseThrow();
        }
        return category;
    }

    @Transactional
    public Set<Tag> resolveTags(String commaSeparated, String createdBy) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return new LinkedHashSet<>();
        }
        // 手动输入和 AI 结果共用同一套去重规则，单篇最多保留 5 个。
        return Arrays.stream(commaSeparated.split("[,，]"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .limit(5)
                .map(value -> findOrCreate(value, createdBy))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Transactional
    public Tag findOrCreate(String name, String createdBy) {
        String displayName = requireTagName(name);
        if (displayName.length() > 60) {
            displayName = displayName.substring(0, 60);
        }
        String normalized = normalizeTagName(displayName);
        String finalDisplayName = displayName;
        return tags.findByNormalizedName(normalized).orElseGet(() -> tags.save(new Tag(
                finalDisplayName, normalized, uniqueTagSlug(finalDisplayName), createdBy)));
    }

    @Transactional
    public Category createCategory(String name, String slug, Long parentId, String description,
            String aiDescription, String aiExclusions, String aiExamples, String aiKeywords) {
        if (name == null || name.isBlank() || name.strip().length() > 80) {
            throw new IllegalArgumentException("分类名称不能为空且不能超过 80 个字符");
        }
        String safeSlug = slug == null ? "" : slug.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-_]+", "-").replaceAll("(^-|-$)", "");
        if (safeSlug.isBlank() || categories.existsBySlug(safeSlug)) {
            throw new IllegalArgumentException("分类 slug 为空或已存在");
        }
        Category parent = null;
        if (parentId != null) {
            parent = categories.findById(parentId).orElseThrow(() -> new IllegalArgumentException("父分类不存在"));
            if (parent.getParent() != null || parent.isSystemCategory()) {
                throw new IllegalArgumentException("分类最多支持两级");
            }
            if (articles.countByCategoryId(parentId) > 0) {
                throw new IllegalArgumentException("该一级分类已有文章，请先迁移文章再创建子分类");
            }
        }
        Category category = new Category(name.strip(), safeSlug);
        category.setParent(parent);
        category.setDescription(optionalText(description, 500, "展示说明"));
        category.setAiDescription(optionalText(aiDescription, 1000, "AI 应归入"));
        category.setAiExclusions(optionalText(aiExclusions, 1000, "AI 不应归入"));
        category.setAiExamples(optionalText(aiExamples, 2000, "AI 示例"));
        category.setAiKeywords(optionalText(aiKeywords, 1000, "AI 关键词"));
        category.setSortOrder(100);
        return categories.save(category);
    }

    @Transactional
    public Category updateCategory(Long id, String name, String slug, String description,
            String aiDescription, String aiExclusions, String aiExamples, String aiKeywords, int sortOrder) {
        Category category = categories.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分类不存在"));
        if (name == null || name.isBlank() || name.strip().length() > 80) {
            throw new IllegalArgumentException("分类名称不能为空且不能超过 80 个字符");
        }
        String safeSlug = safeCategorySlug(slug);
        if (safeSlug.isBlank()) {
            throw new IllegalArgumentException("分类 slug 不能为空");
        }
        if (category.isSystemCategory() && !category.getSlug().equals(safeSlug)) {
            throw new IllegalArgumentException("系统分类不能修改 slug");
        }
        categories.findBySlug(safeSlug)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new IllegalArgumentException("分类 slug 已存在"); });
        category.setName(name.strip());
        category.setSlug(safeSlug);
        category.setDescription(optionalText(description, 500, "展示说明"));
        category.setAiDescription(optionalText(aiDescription, 1000, "AI 应归入"));
        category.setAiExclusions(optionalText(aiExclusions, 1000, "AI 不应归入"));
        category.setAiExamples(optionalText(aiExamples, 2000, "AI 示例"));
        category.setAiKeywords(optionalText(aiKeywords, 1000, "AI 关键词"));
        category.setSortOrder(Math.max(-10_000, Math.min(10_000, sortOrder)));
        return category;
    }

    @Transactional
    public void toggleCategory(Long id) {
        Category category = categories.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分类不存在"));
        if (category.isSystemCategory()) {
            throw new IllegalArgumentException("系统分类必须保持启用");
        }
        category.setEnabled(!category.isEnabled());
    }

    @Transactional
    public void deleteCategory(Long id) {
        Category category = categories.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分类不存在"));
        if (category.isSystemCategory()) {
            throw new IllegalArgumentException("系统分类不能删除");
        }
        if (categories.existsByParentId(id)) {
            throw new IllegalArgumentException("请先删除或迁移该分类的子分类");
        }
        if (articles.countByCategoryId(id) > 0) {
            throw new IllegalArgumentException("仍有文章使用该分类，请先迁移文章");
        }
        categories.delete(category);
    }

    @Transactional
    public Tag renameTag(Long id, String name) {
        Tag tag = tags.findById(id).orElseThrow(() -> new IllegalArgumentException("标签不存在"));
        String displayName = requireTagName(name);
        String normalized = normalizeTagName(displayName);
        tags.findByNormalizedName(normalized)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new IllegalArgumentException("同名标签已存在，请使用合并功能"); });
        tag.setName(displayName);
        tag.setNormalizedName(normalized);
        return tag;
    }

    @Transactional
    public void mergeTag(Long sourceId, Long targetId) {
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("不能将标签合并到自身");
        }
        Tag source = tags.findById(sourceId).orElseThrow(() -> new IllegalArgumentException("源标签不存在"));
        Tag target = tags.findById(targetId).orElseThrow(() -> new IllegalArgumentException("目标标签不存在"));
        for (Article article : articles.findAllByTagId(sourceId)) {
            article.getTags().remove(source);
            article.getTags().add(target);
        }
        tags.delete(source);
    }

    @Transactional
    public void deleteTag(Long id) {
        Tag tag = tags.findById(id).orElseThrow(() -> new IllegalArgumentException("标签不存在"));
        if (articles.countByTagsId(id) > 0) {
            throw new IllegalArgumentException("标签仍被文章使用，请先合并或移除关联");
        }
        tags.delete(tag);
    }

    private String uniqueTagSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "tag";
        }
        String candidate = base;
        while (tags.existsBySlug(candidate)) {
            candidate = base + "-" + UUID.randomUUID().toString().substring(0, 6);
        }
        return candidate;
    }

    private String safeCategorySlug(String slug) {
        return slug == null ? "" : slug.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-_]+", "-").replaceAll("(^-|-$)", "");
    }

    private String requireTagName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("标签名称不能为空");
        }
        String value = name.strip();
        if (value.length() > 60) {
            throw new IllegalArgumentException("标签名称不能超过 60 个字符");
        }
        return value;
    }

    private String normalizeTagName(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String optionalText(String value, int maxLength, String fieldName) {
        String normalized = blankToNull(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }
}
