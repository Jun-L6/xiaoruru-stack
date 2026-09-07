package beer.xiaoruru.ai;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
        var pending = client.sendAsync(request, info -> new LimitedBody());
        try {
            // The deadline includes the entire body, not just response headers.
            var response = pending.get(connection.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                // Do not expose response bodies, URLs or credentials in execution logs.
                throw new IllegalStateException("AI 接口返回 HTTP " + response.statusCode()
                        + "，请检查接口、API Key 和模型配置。");
            }
            byte[] bytes = response.body();
            String content;
            try {
                content = mapper.readTree(bytes).path("choices").path(0).path("message").path("content").asText("");
            } catch (RuntimeException exception) {
                throw new IllegalStateException("AI 接口返回了无效的 JSON。");
            }
            if (content.isBlank()) throw new IllegalStateException("AI 接口未返回文本内容。");
            return content;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 请求已取消。");
        } catch (TimeoutException exception) {
            throw new IllegalStateException("AI 接口请求超时。");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof BodyTooLargeException) {
                throw new IllegalStateException("AI 返回内容过大。");
            }
            if (exception.getCause() instanceof java.net.http.HttpTimeoutException) {
                throw new IllegalStateException("AI 接口请求超时。");
            }
            throw new IllegalStateException("无法连接 AI 接口，请检查网络和服务配置。");
        } finally {
            pending.cancel(true);
        }
    }

    @jakarta.annotation.PreDestroy
    void close() { client.shutdownNow(); }

    private static class BodyTooLargeException extends java.io.IOException {}

    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 2_000_000 - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new BodyTooLargeException());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
