package beer.xiaoruru.backup;

import beer.xiaoruru.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "backup_records")
public class BackupRecord extends BaseEntity {
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;
    @Column(name = "relative_path", length = 500)
    private String relativePath;
    @Column(name = "file_size")
    private Long fileSize;
    @Column(length = 64)
    private String sha256;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BackupType type;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BackupStatus status = BackupStatus.RUNNING;
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    protected BackupRecord() {}

    public BackupRecord(String fileName, BackupType type) {
        this.fileName = fileName;
        this.type = type;
    }

    public String getFileName() { return fileName; }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public BackupType getType() { return type; }
    public BackupStatus getStatus() { return status; }
    public void setStatus(BackupStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
