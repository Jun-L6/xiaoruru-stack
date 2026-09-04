package beer.xiaoruru.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "blog")
public record BlogProperties(
        @NotNull Path dataDir,
        @NotBlank String publicUrl,
        @Valid @NotNull Admin admin,
        @Valid @NotNull Ai ai,
        @Valid @NotNull Backup backup,
        @Valid @NotNull Upload upload
) {
    public record Admin(@NotNull String secretHash, String secret, @NotNull Duration sessionTimeout) {
        @Override
        public String toString() {
            return "Admin[credentials=REDACTED, sessionTimeout=" + sessionTimeout + "]";
        }
    }

    public record Ai(
            boolean enabled,
            @NotNull Duration timeout,
            @NotBlank String model,
            @NotBlank String endpoint,
            @Valid @NotNull Classification classification
    ) {}

    public record Classification(
            boolean autoRun,
            @NotNull Duration delayAfterSave,
            @Min(0) @Max(1) double minimumConfidence,
            @Min(0) @Max(1) double reviewConfidence,
            @Min(0) int minTags,
            @Min(1) int maxTags
    ) {}

    public record Backup(boolean enabled, @Min(1) int retentionCount) {}

    public record Upload(@NotNull org.springframework.util.unit.DataSize maxFileSize) {}
}
