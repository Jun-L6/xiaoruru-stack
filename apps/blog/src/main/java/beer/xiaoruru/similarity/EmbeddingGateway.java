package beer.xiaoruru.similarity;

import beer.xiaoruru.ai.AiSettingsService;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class EmbeddingGateway {
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final int BATCH_SIZE = 64;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    EmbeddingGateway(ObjectMapper mapper) { this.mapper = mapper; }

    List<float[]> embed(AiSettingsService.EmbeddingConnection connection, List<String> input) {
        if (input.isEmpty()) return List.of();
        List<float[]> result = new ArrayList<>();
        for (int start = 0; start < input.size(); start += BATCH_SIZE) {
            result.addAll(request(connection, input.subList(start, Math.min(input.size(), start + BATCH_SIZE))));
        }
        return result;
    }

    private List<float[]> request(AiSettingsService.EmbeddingConnection connection, List<String> input) {
        String body = mapper.writeValueAsString(Map.of("model", connection.model(), "input", input));
        HttpRequest request = HttpRequest.newBuilder(connection.endpoint()).timeout(connection.timeout())
                .header("Authorization", "Bearer " + connection.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Embedding 接口返回 HTTP " + response.statusCode() + "。");
                }
                if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("Embedding 响应过大。");
                JsonNode root = mapper.readTree(bytes);
                List<Item> items = new ArrayList<>();
                for (JsonNode data : root.path("data")) {
                    JsonNode vector = data.path("embedding");
                    if (!vector.isArray() || vector.isEmpty()) throw new IllegalStateException("Embedding 响应缺少向量。");
                    float[] values = new float[vector.size()];
                    for (int i = 0; i < vector.size(); i++) {
                        double value = vector.get(i).doubleValue();
                        if (!Double.isFinite(value)) throw new IllegalStateException("Embedding 响应包含无效数值。");
                        values[i] = (float) value;
                    }
                    items.add(new Item(data.path("index").asInt(items.size()), values));
                }
                items.sort(Comparator.comparingInt(Item::index));
                if (items.size() != input.size()) throw new IllegalStateException("Embedding 返回数量与请求不一致。");
                int dimensions = items.get(0).vector().length;
                if (dimensions < 8 || dimensions > 16_384
                        || items.stream().anyMatch(item -> item.vector().length != dimensions)) {
                    throw new IllegalStateException("Embedding 向量维度不一致或超出安全范围。");
                }
                return items.stream().map(Item::vector).toList();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding 请求被中断。");
        } catch (IOException exception) {
            throw new IllegalStateException("无法连接 Embedding 接口。");
        }
    }

    private record Item(int index, float[] vector) {}
}
