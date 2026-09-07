package beer.xiaoruru.taxonomy;

import beer.xiaoruru.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "categories", uniqueConstraints = @UniqueConstraint(name = "uk_category_slug", columnNames = "slug"))
public class Category extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 100)
    private String slug;

    @Column(length = 500)
    private String description;

    @Column(name = "ai_description", length = 1000)
    private String aiDescription;

    @Column(name = "ai_exclusions", length = 1000)
    private String aiExclusions;

    @Column(name = "ai_examples", length = 2000)
    private String aiExamples;

    @Column(name = "ai_keywords", length = 1000)
    private String aiKeywords;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "system_category", nullable = false)
    private boolean systemCategory;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected Category() {}

    public Category(String name, String slug) {
        this.name = name;
        this.slug = slug;
    }

    public boolean isLeaf() {
        return parent != null || systemCategory;
    }

    public String getPathName() {
        return parent == null ? name : parent.getName() + " / " + name;
    }

    public Category getParent() { return parent; }
    public void setParent(Category parent) { this.parent = parent; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAiDescription() { return aiDescription; }
    public void setAiDescription(String aiDescription) { this.aiDescription = aiDescription; }
    public String getAiExclusions() { return aiExclusions; }
    public void setAiExclusions(String aiExclusions) { this.aiExclusions = aiExclusions; }
    public String getAiExamples() { return aiExamples; }
    public void setAiExamples(String aiExamples) { this.aiExamples = aiExamples; }
    public String getAiKeywords() { return aiKeywords; }
    public void setAiKeywords(String aiKeywords) { this.aiKeywords = aiKeywords; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isSystemCategory() { return systemCategory; }
    public void setSystemCategory(boolean systemCategory) { this.systemCategory = systemCategory; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
