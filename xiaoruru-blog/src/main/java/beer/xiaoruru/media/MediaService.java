package beer.xiaoruru.media;

import beer.xiaoruru.article.ArticleRepository;
import beer.xiaoruru.common.Hashing;
import beer.xiaoruru.config.BlogProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.file.StandardOpenOption;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "image/gif", ".gif",
            "application/pdf", ".pdf",
            "application/zip", ".zip",
            "text/plain", ".txt",
            "text/markdown", ".md"
    );
    private static final Map<String, Set<String>> ACCEPTED_SOURCE_EXTENSIONS = Map.of(
            "image/jpeg", Set.of(".jpg", ".jpeg"),
            "image/png", Set.of(".png"),
            "image/webp", Set.of(".webp"),
            "image/gif", Set.of(".gif"),
            "application/pdf", Set.of(".pdf"),
            "application/zip", Set.of(".zip"),
            "text/plain", Set.of(".txt"),
            "text/markdown", Set.of(".md", ".markdown")
    );

    private final MediaAssetRepository media;
    private final ArticleRepository articles;
    private final BlogProperties properties;

    public MediaService(MediaAssetRepository media, ArticleRepository articles, BlogProperties properties) {
        this.media = media;
        this.articles = articles;
        this.properties = properties;
    }

    @Transactional
    public MediaAsset upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择文件");
        }
        if (file.getSize() > properties.upload().maxFileSize().toBytes()) {
            throw new IllegalArgumentException("文件超过大小限制");
        }
        byte[] bytes = file.getBytes();
        String mime = detectMime(bytes, file.getOriginalFilename());
        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = EXTENSIONS.get(mime);
        if (extension == null) {
            throw new IllegalArgumentException("不支持该文件类型");
        }
        validateSourceNameAndType(originalName, file.getContentType(), mime);
        ImageDimensions dimensions = null;
        if (mime.startsWith("image/") && !mime.equals("image/webp")) {
            dimensions = readImageDimensions(bytes);
        }

        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String storedName = UUID.randomUUID() + extension;
        Path uploadRoot = properties.dataDir().toAbsolutePath().normalize().resolve("uploads");
        Path directory = uploadRoot.resolve(month).normalize();
        if (!directory.startsWith(uploadRoot)) {
            throw new IllegalStateException("Invalid upload directory");
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(uploadRoot) || Files.isSymbolicLink(directory.getParent())
                || Files.isSymbolicLink(directory)) {
            throw new IllegalStateException("上传目录不能是符号链接");
        }
        Path target = directory.resolve(storedName).normalize();
        if (!target.startsWith(directory) || Files.exists(target)) {
            throw new IllegalStateException("Invalid upload target");
        }
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);

        MediaAsset asset = new MediaAsset(originalName,
                month + "/" + storedName, mime, bytes.length, Hashing.sha256(bytes));
        if (dimensions != null) {
            asset.setWidth(dimensions.width());
            asset.setHeight(dimensions.height());
        }
        try {
            return media.saveAndFlush(asset);
        } catch (RuntimeException exception) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public MediaFile load(Long id) throws IOException {
        MediaAsset asset = media.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("资源不存在"));
        Path uploadRoot = properties.dataDir().toAbsolutePath().normalize().resolve("uploads");
        Path path = uploadRoot.resolve(asset.getRelativePath()).normalize();
        if (!path.startsWith(uploadRoot) || !Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("资源文件不存在");
        }
        Resource resource = new UrlResource(path.toUri());
        return new MediaFile(asset, resource);
    }

    @Transactional
    public void delete(Long id) throws IOException {
        MediaAsset asset = media.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("资源不存在"));
        if (articles.existsByContentContaining("/media/" + id + "/")) {
            throw new IllegalArgumentException("资源仍被文章引用，不能删除");
        }
        Path uploadRoot = properties.dataDir().toAbsolutePath().normalize().resolve("uploads");
        Path source = uploadRoot.resolve(asset.getRelativePath()).normalize();
        if (!source.startsWith(uploadRoot) || Files.isSymbolicLink(source)) {
            throw new IllegalStateException("Invalid media path");
        }
        if (Files.exists(source)) {
            Path trash = uploadRoot.resolve(".trash").normalize();
            Files.createDirectories(trash);
            Path target = trash.resolve(id + "-" + source.getFileName()).normalize();
            if (!target.startsWith(trash)) {
                throw new IllegalStateException("Invalid media trash path");
            }
            Files.move(source, target);
        }
        asset.setDeleted(true);
    }

    private String detectMime(byte[] bytes, String originalName) {
        if (starts(bytes, 0xff, 0xd8, 0xff)) return "image/jpeg";
        if (starts(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return "image/png";
        if (asciiStarts(bytes, "GIF87a") || asciiStarts(bytes, "GIF89a")) return "image/gif";
        if (bytes.length >= 12 && asciiAt(bytes, 0, "RIFF") && asciiAt(bytes, 8, "WEBP")) return "image/webp";
        if (asciiStarts(bytes, "%PDF-")) return "application/pdf";
        if (starts(bytes, 0x50, 0x4b, 0x03, 0x04)) return "application/zip";
        if (isText(bytes)) {
            String lower = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
            return lower.endsWith(".md") || lower.endsWith(".markdown") ? "text/markdown" : "text/plain";
        }
        throw new IllegalArgumentException("无法识别或不允许的文件类型");
    }

    private boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 0xff) != prefix[i]) return false;
        return true;
    }

    private boolean asciiStarts(byte[] bytes, String prefix) { return asciiAt(bytes, 0, prefix); }

    private boolean asciiAt(byte[] bytes, int offset, String value) {
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (bytes[offset + i] != expected[i]) return false;
        return true;
    }

    private boolean isText(byte[] bytes) {
        int sample = Math.min(bytes.length, 8192);
        for (int i = 0; i < sample; i++) if (bytes[i] == 0) return false;
        return true;
    }

    private ImageDimensions readImageDimensions(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new IllegalArgumentException("图片内容无效");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片内容无效");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (width <= 0 || height <= 0 || width > 12_000 || height > 12_000 || pixels > 40_000_000L) {
                    throw new IllegalArgumentException("图片尺寸超过安全限制");
                }
                return new ImageDimensions(width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("图片内容损坏或不完整", exception);
        }
    }

    private void validateSourceNameAndType(String originalName, String declaredMime, String detectedMime) {
        String lower = originalName.toLowerCase(Locale.ROOT);
        boolean extensionMatches = ACCEPTED_SOURCE_EXTENSIONS.getOrDefault(detectedMime, Set.of()).stream()
                .anyMatch(lower::endsWith);
        if (!extensionMatches) {
            throw new IllegalArgumentException("文件扩展名与实际内容不匹配");
        }
        if (declaredMime != null && !declaredMime.isBlank()
                && !"application/octet-stream".equalsIgnoreCase(declaredMime)
                && EXTENSIONS.containsKey(declaredMime.toLowerCase(Locale.ROOT))
                && !detectedMime.equalsIgnoreCase(declaredMime)) {
            throw new IllegalArgumentException("浏览器声明的文件类型与实际内容不匹配");
        }
    }

    private String safeOriginalName(String name) {
        if (name == null || name.isBlank()) return "file";
        String normalized = name.replace('\\', '/');
        String safe = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "").strip();
        if (safe.isBlank()) safe = "file";
        return safe.length() > 255 ? safe.substring(safe.length() - 255) : safe;
    }

    public record MediaFile(MediaAsset asset, Resource resource) {}
    private record ImageDimensions(int width, int height) {}
}
