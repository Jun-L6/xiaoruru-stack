package beer.xiaoruru.ai;

import beer.xiaoruru.config.BlogProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 连接配置的唯一读写入口。
 *
 * <p>后台保存的配置优先于 YAML；YAML 仅在数据库尚无设置时生效。
 * API Key 使用数据目录内的 AES-GCM 密钥加密，对页面和日志只暴露“是否已配置”。
 */
@Service
public class AiSettingsService {
    public static final String CPA_URL = "http://cli-proxy-api:8317";
    private final AiSettingsRepository repository;
    private final ObjectMapper mapper;
    private final Path keyPath;
    private final TransactionTemplate transactions;
    private final BlogProperties.OpenAi yaml;

    public AiSettingsService(AiSettingsRepository repository, ObjectMapper mapper, BlogProperties properties,
                             PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.mapper = mapper;
        this.keyPath = properties.dataDir().resolve("secrets/ai.key");
        this.transactions = new TransactionTemplate(transactionManager);
        this.yaml = properties.ai().openai();
    }

    public record Profile(String baseUrl, String encryptedKey, String model, String completionsPath,
                          int timeoutSeconds, Double temperature) {}
    public record EmbeddingProfile(String mode, String baseUrl, String encryptedKey, String model,
                                   String embeddingsPath, int timeoutSeconds) {}
    public record Document(String mode, Profile cpa, Profile external, EmbeddingProfile embedding) {}
    public record Selection(boolean enabled, Profile profile, String apiKeyOverride) {
        @Override public String toString() { return "AiSelection[credentials=REDACTED]"; }
    }
    public record Form(String mode, String baseUrl, String apiKey, String model, String completionsPath,
                       int timeoutSeconds, Double temperature, boolean clearKey) {
        @Override public String toString() { return "AiSettingsForm[REDACTED]"; }
    }
    public record EmbeddingForm(String mode, String baseUrl, String apiKey, String model,
                                String embeddingsPath, int timeoutSeconds, boolean clearKey) {
        @Override public String toString() { return "EmbeddingSettingsForm[REDACTED]"; }
    }
    public record Connection(URI endpoint, String apiKey, String model, Duration timeout, Double temperature) {
        @Override public String toString() { return "AiConnection[REDACTED]"; }
    }
    public record EmbeddingConnection(URI endpoint, String apiKey, String model, Duration timeout) {
        @Override public String toString() { return "EmbeddingConnection[REDACTED]"; }
    }

    private Profile defaults(String url) {
        return new Profile(url, "", "", "/v1/chat/completions", 45, null);
    }

    private EmbeddingProfile embeddingDefaults() {
        return new EmbeddingProfile("disabled", "https://api.example.com", "", "", "/v1/embeddings", 45);
    }

    private Document document() {
        return repository.findById(1L).map(row -> normalize(mapper.readValue(row.getDocument(), Document.class)))
                .orElseGet(() -> new Document("none", defaults(CPA_URL), defaults("https://api.example.com"),
                        embeddingDefaults()));
    }

    private Document normalize(Document doc) {
        if (doc == null) return new Document("none", defaults(CPA_URL), defaults("https://api.example.com"),
                embeddingDefaults());
        return new Document(doc.mode() == null ? "none" : doc.mode(),
                doc.cpa() == null ? defaults(CPA_URL) : doc.cpa(),
                doc.external() == null ? defaults("https://api.example.com") : doc.external(),
                doc.embedding() == null ? embeddingDefaults() : doc.embedding());
    }

    public boolean enabled() { return selection().enabled(); }

    private Profile selected(Document doc) { return doc.mode().equals("cpa") ? doc.cpa() : doc.external(); }

    public Duration timeout() { return Duration.ofSeconds(selection().profile().timeoutSeconds()); }
    public String model() { return selection().profile().model(); }
    public String endpoint() { return selection().profile().baseUrl(); }

    public Selection selection() {
        // 一旦后台产生持久化设置，它就是运行时唯一数据源。
        var persisted = repository.findById(1L);
        if (persisted.isPresent()) {
            Document doc = normalize(mapper.readValue(persisted.orElseThrow().getDocument(), Document.class));
            return new Selection(!doc.mode().equals("none"), selected(doc), "");
        }
        if (yaml.enabled()) {
            return new Selection(true, yamlProfile(), value(yaml.apiKey()));
        }
        return new Selection(false, defaults("https://api.example.com"), "");
    }

    public Map<String, Object> view() {
        if (repository.findById(1L).isEmpty() && yaml.enabled()) {
            Profile profile = yamlProfile();
            return Map.of("mode", "external", "cpa", publicProfile(defaults(CPA_URL)),
                    "external", publicProfile(profile, !value(yaml.apiKey()).isBlank()), "source", "yaml");
        }
        Document doc = document();
        return Map.of("mode", doc.mode(), "cpa", publicProfile(doc.cpa()), "external", publicProfile(doc.external()));
    }

    public Map<String, Object> embeddingView() {
        EmbeddingProfile profile = document().embedding();
        return Map.of("mode", profile.mode(), "baseUrl", profile.baseUrl(),
                "hasKey", !profile.encryptedKey().isBlank(), "model", profile.model(),
                "embeddingsPath", profile.embeddingsPath(), "timeoutSeconds", profile.timeoutSeconds());
    }

    public boolean embeddingEnabled() {
        return !"disabled".equals(document().embedding().mode());
    }

    private Map<String, Object> publicProfile(Profile profile) {
        return publicProfile(profile, !profile.encryptedKey().isBlank());
    }

    private Map<String, Object> publicProfile(Profile profile, boolean hasKey) {
        return Map.of("baseUrl", profile.baseUrl(), "hasKey", hasKey,
                "model", profile.model(), "completionsPath", profile.completionsPath(),
                "timeoutSeconds", profile.timeoutSeconds(),
                "temperature", profile.temperature() == null ? "" : profile.temperature().toString());
    }

    public synchronized void save(Form form) {
        // 锁保持到事务提交，避免两个浏览器页签相互覆盖模式配置。
        transactions.executeWithoutResult(status -> saveWithinTransaction(form));
    }

    private void saveWithinTransaction(Form form) {
        if (form.mode() == null || !java.util.Set.of("none", "cpa", "external").contains(form.mode())) {
            throw new IllegalArgumentException("请选择关闭、CPA 或外部模型。");
        }
        Document old = document();
        Document next;
        if (form.clearKey() && !form.mode().equals("none")) {
            Profile previous = form.mode().equals("cpa") ? old.cpa() : old.external();
            Profile cleared = new Profile(previous.baseUrl(), "", previous.model(),
                    previous.completionsPath(), previous.timeoutSeconds(), previous.temperature());
            next = form.mode().equals("cpa")
                    ? new Document("none", cleared, old.external(), old.embedding())
                    : new Document("none", old.cpa(), cleared, old.embedding());
        } else if (form.mode().equals("none")) {
            next = new Document("none", old.cpa(), old.external(), old.embedding());
        } else {
            Profile previous = form.mode().equals("cpa") ? old.cpa() : old.external();
            String base = form.mode().equals("cpa") ? CPA_URL : value(form.baseUrl()).replaceAll("/+$", "");
            String path = value(form.completionsPath());
            String model = value(form.model());
            URI uri;
            try { uri = URI.create(base); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("接口地址格式不正确。"); }
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || base.length() > 1000) {
                throw new IllegalArgumentException("接口地址必须是 HTTP(S) URL，不得包含账号、查询参数或片段。");
            }
            if (!path.matches("/[a-zA-Z0-9/_-]+") || path.contains("//") || path.length() > 200) {
                throw new IllegalArgumentException("调用路径格式不正确，例如 /v1/chat/completions。");
            }
            if (!path.endsWith("/chat/completions")) {
                throw new IllegalArgumentException("Spring AI 调用路径必须以 /chat/completions 结尾。");
            }
            if (model.isBlank() || model.length() > 200 || model.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("请填写模型名称（最多 200 字符）。");
            }
            if (form.timeoutSeconds() < 1 || form.timeoutSeconds() > 300) {
                throw new IllegalArgumentException("超时范围为 1 至 300 秒。");
            }
            if (form.temperature() != null && (!Double.isFinite(form.temperature())
                    || form.temperature() < 0 || form.temperature() > 2)) {
                throw new IllegalArgumentException("Temperature 范围为 0 至 2，留空使用模型默认值。");
            }
            String supplied = value(form.apiKey());
            if (supplied.length() > 4096 || supplied.chars().anyMatch(ch -> ch < 33 || ch > 126)) {
                throw new IllegalArgumentException("API Key 格式不正确。");
            }
            // 外部地址变更时必须重新提供密钥，禁止将旧密钥静默发送给新端点。
            String key = form.clearKey() || !base.equals(previous.baseUrl()) ? "" : previous.encryptedKey();
            if (!supplied.isBlank() && !form.clearKey()) key = encrypt(supplied);
            if (key.isBlank()) throw new IllegalArgumentException("启用 AI 时必须填写 API Key；更换地址后请重新填写。");
            Profile profile = new Profile(base, key, model, path, form.timeoutSeconds(), form.temperature());
            next = form.mode().equals("cpa")
                    ? new Document("cpa", profile, old.external(), old.embedding())
                    : new Document("external", old.cpa(), profile, old.embedding());
        }
        AiSettings row = repository.findById(1L).orElseGet(() -> new AiSettings(""));
        row.setDocument(mapper.writeValueAsString(next));
        repository.saveAndFlush(row);
    }

    public synchronized void saveEmbedding(EmbeddingForm form) {
        transactions.executeWithoutResult(status -> {
            if (form.mode() == null || !java.util.Set.of("disabled", "reuse", "external").contains(form.mode())) {
                throw new IllegalArgumentException("请选择关闭、复用当前 AI 接口或外部 Embedding 接口。");
            }
            Document old = document();
            EmbeddingProfile previous = old.embedding();
            if ("disabled".equals(form.mode()) || form.clearKey()) {
                EmbeddingProfile disabled = new EmbeddingProfile("disabled", previous.baseUrl(),
                        form.clearKey() ? "" : previous.encryptedKey(), previous.model(),
                        previous.embeddingsPath(), previous.timeoutSeconds());
                saveDocument(new Document(old.mode(), old.cpa(), old.external(), disabled));
                return;
            }
            String base = "reuse".equals(form.mode()) ? previous.baseUrl() : value(form.baseUrl()).replaceAll("/+$", "");
            String path = value(form.embeddingsPath());
            String model = value(form.model());
            validateEndpoint("reuse".equals(form.mode()) ? "https://api.example.com" : base, path, "/embeddings");
            if (model.isBlank() || model.length() > 200 || model.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("请填写 Embedding 模型名称（最多 200 字符）。");
            }
            if (form.timeoutSeconds() < 1 || form.timeoutSeconds() > 300) {
                throw new IllegalArgumentException("Embedding 超时范围为 1 至 300 秒。");
            }
            String encrypted = previous.encryptedKey();
            if ("external".equals(form.mode())) {
                String supplied = value(form.apiKey());
                if (supplied.length() > 4096 || supplied.chars().anyMatch(ch -> ch < 33 || ch > 126)) {
                    throw new IllegalArgumentException("Embedding API Key 格式不正确。");
                }
                if (!base.equals(previous.baseUrl())) encrypted = "";
                if (!supplied.isBlank()) encrypted = encrypt(supplied);
                if (encrypted.isBlank()) throw new IllegalArgumentException("外部 Embedding 接口必须填写 API Key。");
            }
            EmbeddingProfile next = new EmbeddingProfile(form.mode(), base, encrypted, model, path,
                    form.timeoutSeconds());
            saveDocument(new Document(old.mode(), old.cpa(), old.external(), next));
        });
    }

    private void saveDocument(Document document) {
        AiSettings row = repository.findById(1L).orElseGet(() -> new AiSettings(""));
        row.setDocument(mapper.writeValueAsString(document));
        repository.saveAndFlush(row);
    }

    private void validateEndpoint(String base, String path, String requiredSuffix) {
        URI uri;
        try { uri = URI.create(base); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("接口地址格式不正确。"); }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535 || base.length() > 1000) {
            throw new IllegalArgumentException("接口地址必须是 HTTP(S) URL，不得包含账号、查询参数或片段。");
        }
        if (!path.matches("/[a-zA-Z0-9/_-]+") || path.contains("//") || path.length() > 200
                || !path.endsWith(requiredSuffix)) {
            throw new IllegalArgumentException("调用路径必须以 " + requiredSuffix + " 结尾。");
        }
    }

    public Connection connection() {
        return connection(selection());
    }

    public Connection connection(Selection selection) {
        if (!selection.enabled()) throw new IllegalStateException("AI 已关闭，请在后台 AI 设置中启用。");
        Profile profile = selection.profile();
        String apiKey = value(selection.apiKeyOverride());
        if (apiKey.isBlank()) {
            apiKey = decrypt(profile.encryptedKey());
        }
        if (apiKey.isBlank() || profile.model().isBlank()) {
            throw new IllegalStateException("AI 配置缺少 API Key 或模型名称。");
        }
        return new Connection(URI.create(profile.baseUrl() + profile.completionsPath()),
                apiKey, profile.model(),
                Duration.ofSeconds(profile.timeoutSeconds()), profile.temperature());
    }

    public EmbeddingConnection embeddingConnection() {
        Document doc = document();
        EmbeddingProfile embedding = doc.embedding();
        if ("disabled".equals(embedding.mode())) {
            throw new IllegalStateException("语义相似检测尚未启用。");
        }
        String base;
        String apiKey;
        if ("reuse".equals(embedding.mode())) {
            Selection selection = selection();
            if (!selection.enabled()) throw new IllegalStateException("当前 AI 接口已关闭，无法复用它调用 Embedding。");
            base = selection.profile().baseUrl();
            apiKey = value(selection.apiKeyOverride());
            if (apiKey.isBlank()) apiKey = decrypt(selection.profile().encryptedKey());
        } else {
            base = embedding.baseUrl();
            apiKey = decrypt(embedding.encryptedKey());
        }
        if (apiKey.isBlank() || embedding.model().isBlank()) {
            throw new IllegalStateException("Embedding 配置缺少 API Key 或模型名称。");
        }
        return new EmbeddingConnection(URI.create(base.replaceAll("/+$", "") + embedding.embeddingsPath()),
                apiKey, embedding.model(), Duration.ofSeconds(embedding.timeoutSeconds()));
    }

    private Profile yamlProfile() {
        String base = value(yaml.baseUrl()).replaceAll("/+$", "");
        String path = value(yaml.completionsPath());
        if (base.isBlank() || path.isBlank() || !path.endsWith("/chat/completions")) {
            throw new IllegalStateException("YAML 中的 AI 地址或调用路径不正确。");
        }
        long seconds = yaml.timeout().toSeconds();
        if (seconds < 1 || seconds > 300) {
            throw new IllegalStateException("YAML 中的 AI 超时必须在 1 至 300 秒之间。");
        }
        return new Profile(base, "", value(yaml.model()), path, (int) seconds, yaml.temperature());
    }

    private String encrypt(String value) { return crypt(value, true); }
    private String decrypt(String value) { return crypt(value, false); }

    private synchronized String crypt(String value, boolean encrypt) {
        try {
            if (Files.isSymbolicLink(keyPath) || Files.isSymbolicLink(keyPath.getParent())) {
                throw new IllegalStateException("AI 密钥路径不可使用符号链接。");
            }
            if (!Files.exists(keyPath)) {
                if (!encrypt) throw new IllegalStateException("AI 加密密钥文件缺失，请重新填写 API Key。");
                Files.createDirectories(keyPath.getParent());
                Files.setPosixFilePermissions(keyPath.getParent(), PosixFilePermissions.fromString("rwx------"));
                byte[] key = new byte[32];
                new SecureRandom().nextBytes(key);
                Files.write(keyPath, key, StandardOpenOption.CREATE_NEW);
                Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------"));
            }
            byte[] key = Files.readAllBytes(keyPath);
            byte[] nonce = new byte[12];
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            if (encrypt) {
                new SecureRandom().nextBytes(nonce);
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
                byte[] ciphertext = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(ciphertext);
            }
            String[] parts = value.split("\\.", 2);
            nonce = Base64.getDecoder().decode(parts[0]);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("无法读写 AI 加密密钥，请检查 data 目录权限和密钥文件。");
        }
    }

    private static String value(String text) { return text == null ? "" : text.strip(); }
}
