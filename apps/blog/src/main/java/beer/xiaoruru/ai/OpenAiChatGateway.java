package beer.xiaoruru.ai;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI-compatible Chat Completions 接口的轻量网关。
 *
 * <p>客户端在进程内复用，每次调用只使用任务领取时的连接快照。
 * 本类限制响应体大小，并将网络错误转换为不泄露密钥和响应正文的业务错误。
 */
@Component
public class OpenAiChatGateway {
    private static final String COMPLETIONS_SUFFIX = "/chat/completions";
    private static final int MAX_TEXT_LENGTH = 2_000_000;
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final HttpClient client;

    public OpenAiChatGateway(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String complete(AiSettingsService.Connection connection, String system, String user) {
        if (!connection.endpoint().getPath().endsWith(COMPLETIONS_SUFFIX)) {
            throw new IllegalStateException("AI 调用路径必须以 /chat/completions 结尾。");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", connection.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        if (connection.temperature() != null) {
            body.put("temperature", connection.temperature());
        }

        HttpRequest request = HttpRequest.newBuilder(connection.endpoint())
                .timeout(connection.timeout())
                .header("Authorization", "Bearer " + connection.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        long deadline = System.nanoTime() + connection.timeout().toNanos();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = readLimited(stream, deadline);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("AI 接口返回 HTTP " + response.statusCode()
                            + "，请检查接口、API Key 和模型配置。");
                }
                return responseText(mapper.readTree(bytes));
            }
        } catch (HttpTimeoutException exception) {
            throw new IllegalStateException("AI 接口请求超时。");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 接口请求被中断。");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("无法连接 AI 接口，请检查网络和服务配置。");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("AI 接口返回的数据格式不正确。");
        }
    }

    private byte[] readLimited(InputStream stream, long deadline) throws IOException, InterruptedException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new HttpTimeoutException("AI response body timeout");
        }
        FutureTask<byte[]> read = new FutureTask<>(() -> stream.readNBytes(MAX_RESPONSE_BYTES + 1));
        Thread reader = Thread.ofVirtual().name("ai-response-body-reader").start(read);
        byte[] bytes;
        try {
            bytes = read.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            read.cancel(true);
            reader.interrupt();
            throw new HttpTimeoutException("AI response body timeout");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("AI response body read failed", cause);
        }
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException("AI 返回内容过大。");
        }
        return bytes;
    }

    private String responseText(JsonNode root) {
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        String text;
        if (content.isTextual()) {
            text = content.asText();
        } else if (content.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode part : content) {
                JsonNode value = part.isTextual() ? part : part.path("text");
                if (value.isTextual()) {
                    parts.add(value.asText());
                }
            }
            text = String.join("", parts);
        } else {
            text = "";
        }
        if (text.isBlank()) {
            throw new IllegalStateException("AI 接口未返回文本内容。");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalStateException("AI 返回内容过大。");
        }
        return text;
    }
}
