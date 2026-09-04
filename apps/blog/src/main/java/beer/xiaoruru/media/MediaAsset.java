package beer.xiaoruru.media;

import beer.xiaoruru.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "media_assets", uniqueConstraints = @UniqueConstraint(name = "uk_media_path", columnNames = "relative_path"))
public class MediaAsset extends BaseEntity {
    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;
    @Column(name = "relative_path", nullable = false, length = 500)
    private String relativePath;
    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;
    @Column(name = "file_size", nullable = false)
    private long fileSize;
    @Column(nullable = false, length = 64)
    private String sha256;
    private Integer width;
    private Integer height;
    @Column(nullable = false)
    private boolean deleted;

    protected MediaAsset() {}

    public MediaAsset(String originalName, String relativePath, String mimeType, long fileSize, String sha256) {
        this.originalName = originalName;
        this.relativePath = relativePath;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
        this.sha256 = sha256;
    }

    public String getOriginalName() { return originalName; }
    public String getRelativePath() { return relativePath; }
    public String getMimeType() { return mimeType; }
    public long getFileSize() { return fileSize; }
    public String getSha256() { return sha256; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}
