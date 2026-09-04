package beer.xiaoruru.backup;

import beer.xiaoruru.BlogApplication;
import beer.xiaoruru.common.Hashing;
import beer.xiaoruru.config.BlogProperties;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    private final BackupRecordRepository records;
    private final JdbcTemplate jdbc;
    private final BlogProperties properties;
    private final Executor executor;
    private final TransactionTemplate transactions;
    private final AtomicBoolean running = new AtomicBoolean();

    public BackupService(BackupRecordRepository records, DataSource dataSource, BlogProperties properties,
            @Qualifier("backupTaskExecutor") Executor executor, PlatformTransactionManager transactionManager) {
        this.records = records;
        this.jdbc = new JdbcTemplate(dataSource);
        this.properties = properties;
        this.executor = executor;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public BackupRecord start(BackupType type) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有备份任务正在运行");
        }
        String name = "blog-backup-" + FILE_TIME.format(Instant.now()) + "-" + type.name().toLowerCase() + ".zip";
        BackupRecord record = transactions.execute(status -> records.save(new BackupRecord(name, type)));
        try {
            executor.execute(() -> create(record.getId()));
        } catch (RuntimeException exception) {
            running.set(false);
            transactions.executeWithoutResult(status -> markFailed(record.getId(), exception));
            throw exception;
        }
        return record;
    }

    @Scheduled(cron = "${blog.backup.cron:0 0 3 * * *}")
    public void automaticBackup() {
        if (!properties.backup().enabled()) return;
        try {
            start(BackupType.AUTOMATIC);
        } catch (IllegalStateException exception) {
            log.info("Automatic backup skipped: {}", exception.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedBackups() {
        transactions.executeWithoutResult(status -> {
            for (BackupRecord record : records.findByStatus(BackupStatus.RUNNING)) {
                record.setStatus(BackupStatus.FAILED);
                record.setErrorMessage("应用重启或恢复后，原备份任务已中断");
            }
        });
    }

    public Path requireDownload(Long id) {
        BackupRecord record = records.findById(id).orElseThrow(() -> new IllegalArgumentException("备份不存在"));
        if (record.getStatus() != BackupStatus.SUCCEEDED || record.getRelativePath() == null) {
            throw new IllegalArgumentException("备份尚不可下载");
        }
        Path dataRoot = dataRoot();
        Path target = dataRoot.resolve(record.getRelativePath()).normalize();
        Path backupRoot = dataRoot.resolve("backups");
        if (!target.startsWith(backupRoot) || !Files.isRegularFile(target) || Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("备份文件不存在");
        }
        return target;
    }

    private void create(Long recordId) {
        Path tempDirectory = null;
        try {
            Path root = dataRoot();
            Path tempRoot = root.resolve("temp").normalize();
            Path backupRoot = root.resolve("backups").normalize();
            Files.createDirectories(tempRoot);
            Files.createDirectories(backupRoot);
            tempDirectory = Files.createTempDirectory(tempRoot, "backup-").normalize();
            requireWithin(tempDirectory, tempRoot, "backup temp directory");

            Path h2Backup = tempDirectory.resolve("h2-backup.zip");
            jdbc.execute("BACKUP TO '" + h2Backup.toString().replace("'", "''") + "'");

            BackupRecord record = records.findById(recordId).orElseThrow();
            Path partial = backupRoot.resolve(record.getFileName() + ".part").normalize();
            Path target = backupRoot.resolve(record.getFileName()).normalize();
            requireWithin(partial, backupRoot, "partial backup");
            requireWithin(target, backupRoot, "backup target");
            if (Files.exists(partial) || Files.exists(target)) {
                throw new IOException("备份目标文件已存在");
            }

            List<Path> uploads = listUploadFiles(root.resolve("uploads"));
            String checksums = checksums(h2Backup, uploads, root.resolve("uploads"));
            Path aiKey = root.resolve("secrets/ai.key");
            boolean includeAiKey = Files.isRegularFile(aiKey) && !Files.isSymbolicLink(aiKey);
            if (includeAiKey) checksums += Hashing.sha256(aiKey) + "  secrets/ai.key\n";
            String manifest = manifest(record, uploads.size());
            try (OutputStream fileOutput = Files.newOutputStream(partial);
                    ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fileOutput), StandardCharsets.UTF_8)) {
                addText(zip, "manifest.json", manifest);
                addFile(zip, h2Backup, "database/h2-backup.zip");
                if (includeAiKey) {
                    addFile(zip, aiKey, "secrets/ai.key");
                }
                Path uploadRoot = root.resolve("uploads");
                for (Path upload : uploads) {
                    addFile(zip, upload, "uploads/" + uploadRoot.relativize(upload).toString().replace('\\', '/'));
                }
                addText(zip, "checksums.sha256", checksums);
            }
            try (ZipFile ignored = new ZipFile(partial.toFile(), StandardCharsets.UTF_8)) {
                if (ignored.getEntry("manifest.json") == null || ignored.getEntry("database/h2-backup.zip") == null) {
                    throw new IOException("备份包校验失败");
                }
            }
            moveAtomically(partial, target);
            long size = Files.size(target);
            String sha = Hashing.sha256(target);
            transactions.executeWithoutResult(status -> {
                BackupRecord current = records.findById(recordId).orElseThrow();
                current.setRelativePath("backups/" + target.getFileName());
                current.setFileSize(size);
                current.setSha256(sha);
                current.setStatus(BackupStatus.SUCCEEDED);
            });
            cleanupRetention();
        } catch (Exception exception) {
            log.error("Backup {} failed", recordId, exception);
            transactions.executeWithoutResult(status -> markFailed(recordId, exception));
        } finally {
            if (tempDirectory != null) safeDeleteTemporaryTree(tempDirectory);
            running.set(false);
        }
    }

    private List<Path> listUploadFiles(Path uploadRoot) throws IOException {
        if (!Files.isDirectory(uploadRoot)) return List.of();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(uploadRoot)) {
            stream.filter(path -> Files.isRegularFile(path) && !Files.isSymbolicLink(path))
                    .filter(path -> !path.startsWith(uploadRoot.resolve(".trash")))
                    .forEach(files::add);
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private String checksums(Path h2Backup, List<Path> uploads, Path uploadRoot) throws IOException {
        StringBuilder value = new StringBuilder(Hashing.sha256(h2Backup)).append("  database/h2-backup.zip\n");
        for (Path upload : uploads) {
            value.append(Hashing.sha256(upload)).append("  uploads/")
                    .append(uploadRoot.relativize(upload).toString().replace('\\', '/')).append('\n');
        }
        return value.toString();
    }

    private String manifest(BackupRecord record, int uploadCount) {
        String applicationVersion = Optional.ofNullable(
                BlogApplication.class.getPackage().getImplementationVersion()).orElse("development");
        return """
                {
                  "formatVersion": 1,
                  "application": "rurublog",
                  "applicationVersion": "%s",
                  "minimumApplicationVersion": "0.1.0",
                  "createdAt": "%s",
                  "timezone": "%s",
                  "backupType": "%s",
                  "uploadFileCount": %d,
                  "checksumAlgorithm": "SHA-256"
                }
                """.formatted(applicationVersion, Instant.now(), ZoneId.systemDefault(), record.getType(), uploadCount);
    }

    private void addText(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void addFile(ZipOutputStream zip, Path path, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    private void cleanupRetention() {
        List<BackupRecord> automatic = records.findByTypeAndStatusOrderByCreatedAtDesc(
                BackupType.AUTOMATIC, BackupStatus.SUCCEEDED);
        for (int i = properties.backup().retentionCount(); i < automatic.size(); i++) {
            BackupRecord record = automatic.get(i);
            try {
                Path path = requireDownload(record.getId());
                Path backupRoot = dataRoot().resolve("backups").normalize();
                requireWithin(path, backupRoot, "expired automatic backup");
                if (Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
                    Files.delete(path);
                    records.delete(record);
                }
            } catch (IOException | IllegalArgumentException exception) {
                log.warn("Cannot remove expired backup {}: {}", record.getId(), exception.getMessage());
                break;
            }
        }
    }

    private void markFailed(Long id, Exception exception) {
        records.findById(id).ifPresent(record -> {
            record.setStatus(BackupStatus.FAILED);
            record.setErrorMessage(clip(exception.getMessage(), 1000));
        });
    }

    private void safeDeleteTemporaryTree(Path target) {
        Path tempRoot = dataRoot().resolve("temp").normalize();
        if (!target.normalize().startsWith(tempRoot) || target.equals(tempRoot) || Files.isSymbolicLink(target)) {
            log.error("Refusing to clean unexpected temporary path: {}", target);
            return;
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path) || Files.isRegularFile(path) || Files.isDirectory(path)) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException exception) {
            log.warn("Cannot clean backup temporary directory {}: {}", target, exception.getMessage());
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private Path dataRoot() {
        return properties.dataDir().toAbsolutePath().normalize();
    }

    private void requireWithin(Path target, Path root, String label) {
        if (!target.normalize().startsWith(root.normalize()) || target.normalize().equals(root.normalize())) {
            throw new IllegalStateException("Invalid " + label + " path");
        }
    }

    private String clip(String value, int max) {
        if (value == null) return "未知错误";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
