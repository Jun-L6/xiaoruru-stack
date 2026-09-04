package beer.xiaoruru.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"blog.backup.enabled=false", "blog.backup.retention-count=2"})
class BackupIntegrationTest {
    @TempDir static Path dataDirectory;

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(dataDirectory.resolve("database"));
        registry.add("blog.data-dir", () -> dataDirectory.toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:"
                + dataDirectory.resolve("database/blog") + ";DB_CLOSE_ON_EXIT=FALSE");
    }

    @Autowired BackupService service;
    @Autowired BackupRecordRepository records;
    @Autowired beer.xiaoruru.ai.AiSettingsService aiSettings;

    @Test
    void createsValidCompleteBackupAndRetainsOnlyNewestAutomaticFiles() throws Exception {
        aiSettings.save(new beer.xiaoruru.ai.AiSettingsService.Form("cpa", "", "backup-test-key", "test-model",
                "/v1/chat/completions", 10, null, false));
        BackupRecord manual = await(service.start(BackupType.MANUAL).getId());
        assertThat(manual.getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
        Path manualArchive = service.requireDownload(manual.getId());
        assertBackup(manualArchive);

        for (int i = 0; i < 3; i++) await(service.start(BackupType.AUTOMATIC).getId());
        var automatic = records.findByTypeAndStatusOrderByCreatedAtDesc(
                BackupType.AUTOMATIC, BackupStatus.SUCCEEDED);
        assertThat(automatic).hasSize(2);
        assertThat(automatic).allSatisfy(record -> assertThat(service.requireDownload(record.getId())).exists());
        assertThat(manualArchive).exists();
    }

    private BackupRecord await(Long id) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            BackupRecord record = records.findById(id).orElseThrow();
            if (record.getStatus() != BackupStatus.RUNNING) return record;
            Thread.sleep(50);
        }
        throw new AssertionError("备份任务未在 10 秒内完成");
    }

    private void assertBackup(Path archive) throws Exception {
        assertThat(archive).isRegularFile();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            assertThat(zip.getEntry("manifest.json")).isNotNull();
            assertThat(zip.getEntry("database/h2-backup.zip")).isNotNull();
            assertThat(zip.getEntry("checksums.sha256")).isNotNull();
            assertThat(zip.getEntry("secrets/ai.key")).isNotNull();
            assertThat(new String(zip.getInputStream(zip.getEntry("checksums.sha256")).readAllBytes(),
                    StandardCharsets.UTF_8)).contains("secrets/ai.key");
            String manifest = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(manifest).contains("\"formatVersion\": 1", "\"minimumApplicationVersion\"",
                    "\"application\": \"rurublog\"");
        }
        assertThat(Files.size(archive)).isGreaterThan(0);
    }
}
