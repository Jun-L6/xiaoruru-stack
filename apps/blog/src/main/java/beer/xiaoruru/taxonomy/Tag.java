package beer.xiaoruru.taxonomy;

import beer.xiaoruru.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "tags", uniqueConstraints = {
        @UniqueConstraint(name = "uk_tag_normalized_name", columnNames = "normalized_name"),
        @UniqueConstraint(name = "uk_tag_slug", columnNames = "slug")
})
public class Tag extends BaseEntity {

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 60)
    private String normalizedName;

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(name = "created_by", nullable = false, length = 20)
    private String createdBy = "MANUAL";

    protected Tag() {}

    public Tag(String name, String normalizedName, String slug, String createdBy) {
        this.name = name;
        this.normalizedName = normalizedName;
        this.slug = slug;
        this.createdBy = createdBy;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNormalizedName() { return normalizedName; }
    public void setNormalizedName(String normalizedName) { this.normalizedName = normalizedName; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getCreatedBy() { return createdBy; }
}
