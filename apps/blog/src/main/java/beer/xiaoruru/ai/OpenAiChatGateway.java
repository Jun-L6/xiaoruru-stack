package beer.xiaoruru.ai;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpenAiChatGateway {
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public OpenAiChatGateway(ObjectMapper mapper) { this.mapper = mapper; }

    public String complete(AiSettingsService.Connection connection, String system, String user) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", connection.model());
        payload.put("stream", false);
        payload.put("messages", List.of(Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        if (connection.temperature() != null) payload.put("temperature", connection.temperature());
        HttpRequest request = HttpRequest.newBuilder(connection.endpoint())
                .timeout(connection.timeout()).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + connection.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200) {
                    // Do not expose response bodies, URLs or credentials in execution logs.
                    throw new IllegalStateException("AI 接口返回 HTTP " + response.statusCode()
                            + "，请检查接口、API Key 和模型配置。");
                }
                byte[] bytes = body.readNBytes(2_000_001);
                if (bytes.length > 2_000_000) throw new IllegalStateException("AI 返回内容过大。");
                String content;
                try {
                    content = mapper.readTree(bytes).path("choices").path(0).path("message").path("content").asText("");
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("AI 接口返回了无效的 JSON。");
                }
                if (content.isBlank()) throw new IllegalStateException("AI 接口未返回文本内容。");
                return content;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 请求已取消。");
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new IllegalStateException("AI 接口请求超时。");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("无法连接 AI 接口，请检查网络和服务配置。");
        }
    }
}
